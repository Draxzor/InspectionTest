package inspectionTest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class JUnitRunner {
    private List<URL> urls;
    private List<String> testClassNames;
    private boolean isRunning = false;
    Object threadObject;
    Class<?> threadClass;

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

    public int getFailuresCount() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = threadClass.getMethod("join", null);
        method.invoke(threadObject, null);

        method = threadClass.getMethod("getFailuresCount", null);
        int failuresCount = (int) method.invoke(threadObject, null);
        isRunning = false;

        return failuresCount;
    }

    public void startThread() throws Exception {
        URLClassLoader ucl = new URLClassLoader(urls.toArray(new URL[urls.size()]));

        threadClass = ucl.loadClass("inspectionTest.JUnitThread");
        threadObject = threadClass.newInstance();


        Method method = threadClass.getMethod("setClassLoader", new Class[]{URLClassLoader.class});
        method.invoke(threadObject, new Object[]{ucl});


        method = threadClass.getMethod("setTestClassNames", new Class[]{List.class});
        method.invoke(threadObject, new Object[]{testClassNames});

        method = threadClass.getMethod("setContextClassLoader", new Class[]{ClassLoader.class});
        method.invoke(threadObject, new Object[]{ucl});

        method = threadClass.getMethod("start", null);
        method.invoke(threadObject, null);
        isRunning = true;
    }

    public void setTestClassNames(List<String> testClassNames) {
        this.testClassNames = testClassNames;
    }
}
