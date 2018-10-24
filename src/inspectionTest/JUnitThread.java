package inspectionTest;

import org.apache.maven.surefire.util.DirectoryScanner;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

public class JUnitThread extends Thread {
    private URLClassLoader ucl;
    private List<String> testClassNames;
    private List<Class> testClasses;
    private int failuresCount;

    public JUnitThread(){
        super("JUnitThread");
        testClassNames = new ArrayList<>();
        testClasses = new ArrayList<>();
    }

    public void setClassLoader(URLClassLoader classLoader) {
        ucl = classLoader;
    }
    public void setTestClassNames(List<String> testClassNames) {
        this.testClassNames = testClassNames;
    }

    private void loadTestClasses() throws ClassNotFoundException {
        for (String className : testClassNames) {
            if (className.contains("Test"))
                testClasses.add(ucl.loadClass(className));
        }

    }

    @Override
    public void run() {
        try {
            loadTestClasses();
            List<DiscoverySelector> selectorList = new ArrayList<>();
            for (Class c : testClasses) {
                selectorList.add(selectClass(c));
            }
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request().selectors(selectorList).build();

            Launcher launcher = LauncherFactory.create();
            ClassLoader context = Thread.currentThread().getContextClassLoader();
            ClassLoader cl1 = launcher.getClass().getClassLoader();
            ClassLoader cl2 = testClasses.get(0).getClassLoader();
            final SummaryGeneratingListener listener = new SummaryGeneratingListener();

            TestPlan testPlan = launcher.discover(request);
            launcher.registerTestExecutionListeners(listener);
            launcher.execute(request);

            TestExecutionSummary summary = listener.getSummary();

            failuresCount = (int) summary.getTestsFailedCount();
            System.out.println("Tests finished with " + failuresCount + " failures.");

        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }

    }

    public int getFailuresCount() {
        return failuresCount;
    }
}
