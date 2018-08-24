package inspectionTest;

import org.junit.runner.JUnitCore;
import org.junit.runner.Result;


import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

public class JUnitThread implements Runnable {
    private URLClassLoader ucl;
    private ClassLoader previousCL;
    private List<String> testClassNames;
    private List<Class> testClasses;
    private InspectionTestApplication app;

    public JUnitThread(){
        testClassNames = new ArrayList<>();
        testClasses = new ArrayList<>();
    }

    public void setClassLoader(URLClassLoader classLoader) {
        previousCL = Thread.currentThread().getContextClassLoader();
        ucl = classLoader;
    }
    public void setTestClassNames(List<String> testClassNames) {
        this.testClassNames = testClassNames;
    }

    private void loadTestClasses() throws ClassNotFoundException {
        for (String className : testClassNames) {
            testClasses.add(ucl.loadClass(className));
        }
    }

    @Override
    public void run() {
        try {
            loadTestClasses();
            Class<JUnitCore> coreClass = (Class<JUnitCore>) ucl.loadClass("org.junit.runner.JUnitCore");
            Class<Result> resultClass = (Class<Result>) ucl.loadClass("org.junit.runner.Result");
            JUnitCore junit = coreClass.newInstance();

            Result result = junit.run(testClasses.toArray(new Class[testClasses.size()]));
            int failures = result.getFailureCount();
            Thread.currentThread().setContextClassLoader(previousCL);

            app.proceedTestResult(result.getFailureCount());
//            System.out.println(result.getFailureCount());

        } catch (InstantiationException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }

    }

    public void setApp(inspectionTest.InspectionTestApplication app) {
        this.app = app;
    }
}
