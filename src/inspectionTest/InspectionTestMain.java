package inspectionTest;

import com.intellij.codeInspection.InspectionToolCmdlineOptionHelpProvider;
import com.intellij.codeInspection.InspectionsBundle;
import com.intellij.openapi.application.ApplicationStarter;
import org.junit.runner.JUnitCore;

import java.net.MalformedURLException;
import java.util.Arrays;

public class InspectionTestMain implements ApplicationStarter {
    private InspectionTestApplication myApplication;

    @Override
    public String getCommandName() {
        return "test-inspect";
    }

    @Override
    @SuppressWarnings({"HardCodedStringLiteral"})
    public void premain(String[] args) {
        if (args.length < 4) {
            System.err.println("invalid args:" + Arrays.toString(args));
            printHelp();
        }

        //System.setProperty("idea.load.plugins.category", "inspection");
        myApplication = new InspectionTestApplication();

        myApplication.myHelpProvider = new InspectionToolCmdlineOptionHelpProvider() {
            @Override
            public void printHelpAndExit() {
                printHelp();
            }
        };
        myApplication.myProjectPath = args[1];
        myApplication.myStubProfile = args[2];

        if (myApplication.myProjectPath == null
                || myApplication.myStubProfile == null) {
            System.err.println(myApplication.myProjectPath + myApplication.myStubProfile);
            printHelp();
        }


        try {
            for (int i = 3; i < args.length; i++) {
                String arg = args[i];
                if ("-profileName".equals(arg)) {
                    myApplication.myProfileName = args[++i];
                } else if ("-profilePath".equals(arg)) {
                    myApplication.myProfilePath = args[++i];
                } else if ("-d".equals(arg)) {
                    myApplication.mySourceDirectory = args[++i];
                }
                else if ("-v0".equals(arg)) {
                    myApplication.setVerboseLevel(0);
                }
                else if ("-v1".equals(arg)) {
                    myApplication.setVerboseLevel(1);
                }
                else if ("-v2".equals(arg)) {
                    myApplication.setVerboseLevel(2);
                }
                else if ("-v3".equals(arg)) {
                    myApplication.setVerboseLevel(3);
                }
                else {
                    System.err.println("unexpected argument: " + arg);
                    printHelp();
                }
            }
        }
        catch (ArrayIndexOutOfBoundsException e) {
            e.printStackTrace();
            printHelp();
        }

        myApplication.myRunGlobalToolsOnly = System.getProperty("idea.no.local.inspections") != null;
    }

    @Override
    public void main(String[] args) {
        myApplication.myTestClassDirectory = "/home/jetbrains/Documents/puppet_queues/build/classes/java/test/";
        myApplication.myMainClassDirectory = "/home/jetbrains/Documents/puppet_queues/build/classes/java/main/";
        myApplication.startup();
    }

    public static void printHelp() {
        System.out.println(InspectionsBundle.message("inspection.command.line.explanation"));
        System.exit(1);
    }
}
