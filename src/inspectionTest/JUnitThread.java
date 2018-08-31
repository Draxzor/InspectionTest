package inspectionTest;

import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
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
            final SummaryGeneratingListener listener = new SummaryGeneratingListener();

            launcher.registerTestExecutionListeners(listener);
            launcher.execute(request);

            TestExecutionSummary summary = listener.getSummary();

            failuresCount = (int) summary.getTestsFailedCount();
            System.out.println("TESTS FINISHED");

        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }

    }

    public int getFailuresCount() {
        return failuresCount;
    }
}
