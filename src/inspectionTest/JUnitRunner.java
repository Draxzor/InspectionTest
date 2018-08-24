package inspectionTest;

import com.intellij.ide.plugins.cl.PluginClassLoader;
import org.junit.runner.JUnitCore;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class JUnitRunner {
    private List<URL> urls;
    private List<String> testClassNames;

    public JUnitRunner(){
        initURLS();
    }

    private void initURLS() {
        urls = new ArrayList<>(Arrays.asList(((URLClassLoader)ClassLoader.getSystemClassLoader()).getURLs()));
    }

    public void addURL(URL url) {
        urls.add(url);
    }

    public void addURLs(List<URL> urls) {
        this.urls.addAll(urls);
    }



    public Thread doWork(InspectionTestApplication app) throws ClassNotFoundException, IllegalAccessException, InstantiationException, NoSuchMethodException, InvocationTargetException, NoSuchFieldException {
        URLClassLoader ucl = new URLClassLoader(urls.toArray(new URL[urls.size()]));

        Class<?> threadClass = ucl.loadClass("JUnitThread");
        Object threadObject = threadClass.newInstance();

        Method method = threadClass.getMethod("setClassLoader", new Class[]{URLClassLoader.class});
        method.invoke(threadObject, new Object[]{ucl});

        method = threadClass.getMethod("setApp", new Class[]{InspectionTestApplication.class});
        method.invoke(threadObject, new Object[]{app});

        method = threadClass.getMethod("setTestClassNames", new Class[]{List.class});
        method.invoke(threadObject, new Object[]{testClassNames});
        Thread t = new Thread((Runnable)threadObject);
        t.setContextClassLoader(ucl);
        t.start();

        return t;

    }

    public void setTestClassNames(List<String> testClassNames) {
        this.testClassNames = testClassNames;
    }
}
