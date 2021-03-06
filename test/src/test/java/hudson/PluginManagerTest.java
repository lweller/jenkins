/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson;

import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import hudson.PluginManager.UberClassLoader;
import hudson.model.Hudson;
import hudson.scm.SubversionSCM;
import org.apache.commons.io.FileUtils;
import org.jvnet.hudson.test.HudsonTestCase;
import org.jvnet.hudson.test.Url;
import org.jvnet.hudson.test.recipes.WithPlugin;
import org.jvnet.hudson.test.recipes.WithPluginManager;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * @author Kohsuke Kawaguchi
 */
public class PluginManagerTest extends HudsonTestCase {
    @Override
    protected void setUp() throws Exception {
        setPluginManager(null); // use a fresh instance
        super.setUp();
    }

    /**
     * Manual submission form.
     */
    public void testUpload() throws Exception {
        HtmlPage page = new WebClient().goTo("pluginManager/advanced");
        HtmlForm f = page.getFormByName("uploadPlugin");
        File dir = env.temporaryDirectoryAllocator.allocate();
        File plugin = new File(dir, "tasks.hpi");
        FileUtils.copyURLToFile(getClass().getClassLoader().getResource("plugins/tasks.hpi"),plugin);
        f.getInputByName("name").setValueAttribute(plugin.getAbsolutePath());
        submit(f);

        assertTrue( new File(hudson.getRootDir(),"plugins/tasks.hpi").exists() );
    }

    /**
     * Tests the effect of {@link WithPlugin}.
     */
    @WithPlugin("tasks.hpi")
    public void testWithRecipe() throws Exception {
        assertNotNull(hudson.getPlugin("tasks"));
    }

    /**
     * Makes sure that plugins can see Maven2 plugin that's refactored out in 1.296.
     */
    @WithPlugin("tasks.hpi")
    public void testOptionalMavenDependency() throws Exception {
        PluginWrapper.Dependency m2=null;
        PluginWrapper tasks = hudson.getPluginManager().getPlugin("tasks");
        for( PluginWrapper.Dependency d : tasks.getOptionalDependencies() ) {
            if(d.shortName.equals("maven-plugin")) {
                assertNull(m2);
                m2 = d;
            }
        }
        assertNotNull(m2);

        // this actually doesn't really test what we need, though, because
        // I thought test harness is loading the maven classes by itself.
        // TODO: write a separate test that tests the optional dependency loading
        tasks.classLoader.loadClass(hudson.maven.agent.AbortException.class.getName());
    }

    /**
     * Verifies that by the time {@link Plugin#start()} is called, uber classloader is fully functioning.
     * This is necessary as plugin start method can engage in XStream loading activities, and they should
     * resolve all the classes in the system (for example, a plugin X can define an extension point
     * other plugins implement, so when X loads its config it better sees all the implementations defined elsewhere)
     */
    @WithPlugin("tasks.hpi")
    @WithPluginManager(PluginManagerImpl_for_testUberClassLoaderIsAvailableDuringStart.class)
    public void testUberClassLoaderIsAvailableDuringStart() {
        assertTrue(((PluginManagerImpl_for_testUberClassLoaderIsAvailableDuringStart)hudson.pluginManager).tested);
    }

    public class PluginManagerImpl_for_testUberClassLoaderIsAvailableDuringStart extends LocalPluginManager {
        boolean tested;

        public PluginManagerImpl_for_testUberClassLoaderIsAvailableDuringStart(File rootDir) {
            super(rootDir);
        }

        @Override
        protected PluginStrategy createPluginStrategy() {
            return new ClassicPluginStrategy(this) {
                @Override
                public void startPlugin(PluginWrapper plugin) throws Exception {
                    tested = true;

                    // plugins should be already visible in the UberClassLoader
                    assertTrue(!activePlugins.isEmpty());

                    uberClassLoader.loadClass(SubversionSCM.class.getName());
                    uberClassLoader.loadClass("hudson.plugins.tasks.Messages");

                    super.startPlugin(plugin);
                }
            };
        }
    }


    /**
     * Makes sure that thread context classloader isn't used by {@link UberClassLoader}, or else
     * infinite cycle ensues.
     */
    @Url("http://jenkins.361315.n4.nabble.com/channel-example-and-plugin-classes-gives-ClassNotFoundException-td3756092.html")
    public void testUberClassLoaderDoesntUseContextClassLoader() throws Exception {
        Thread t = Thread.currentThread();

        URLClassLoader ucl = new URLClassLoader(new URL[0],hudson.pluginManager.uberClassLoader);

        ClassLoader old = t.getContextClassLoader();
        t.setContextClassLoader(ucl);
        try {
            try {
                ucl.loadClass("No such class");
                fail();
            } catch (ClassNotFoundException e) {
                // as expected
            }

            ucl.loadClass(Hudson.class.getName());
        } finally {
            t.setContextClassLoader(old);
        }
    }

    public void testInstallWithoutRestart() throws Exception {
        URL res = getClass().getClassLoader().getResource("plugins/htmlpublisher.hpi");
        File f = new File(jenkins.getRootDir(), "plugins/htmlpublisher.hpi");
        FileUtils.copyURLToFile(res, f);
        jenkins.pluginManager.dynamicLoad(f);

        Class c = jenkins.getPluginManager().uberClassLoader.loadClass("htmlpublisher.HtmlPublisher$DescriptorImpl");
        assertNotNull(jenkins.getDescriptorByType(c));
    }
}
