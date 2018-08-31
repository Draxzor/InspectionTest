package inspectionTest;

import com.intellij.codeInspection.InspectionToolCmdlineOptionHelpProvider;
import com.intellij.codeInspection.InspectionsBundle;
import com.intellij.openapi.application.ApplicationStarter;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

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
            Set<String> availableArgs = new HashSet<>(Arrays.asList("-profileName", "-profilePath", "-d", "-v0", "-v1", "-v2", "-v3", "-t", "-m"));

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
                else if ("-t".equals(arg)) {
                    while (!availableArgs.contains(args[i + 1])) {
                        myApplication.myTestClassDirectories.add(args[++i]);
                        if (i + 1 >= args.length) break;
                    }
                }
                else if ("-m".equals(arg)) {
                    while (!availableArgs.contains(args[i + 1])) {
                        myApplication.myMainClassDirectories.add(args[++i]);
                        if (i + 1 >= args.length) break;
                    }
                }
                else {
                    System.err.println("unexpected argument: " + arg);
                    printHelp();
                }
            }
            if (!myApplication.myMainClassDirectories.isEmpty())
                myApplication.detectMainRoots = false;
            if (!myApplication.myTestClassDirectories.isEmpty())
                myApplication.detectTestRoots = false;
        }
        catch (ArrayIndexOutOfBoundsException e) {
            e.printStackTrace();
            printHelp();
        }

        myApplication.myRunGlobalToolsOnly = System.getProperty("idea.no.local.inspections") != null;
    }

    @Override
    public void main(String[] args) {
        myApplication.startup();
    }

    public static void printHelp() {
        String help = "Expected parameters: <project_file_path> <inspection_profile> \n " +
        "<inspections_profile> -- use here profile name configured in the project or locally or path to the inspection profile; can be stabbed when one of the -e|-profileName|-profilePath is used\n" +
        "[<options>]\n " +
        "Available options are:\n" +
        "-d <directory_path>  --  directory to be inspected. Optional. Whole project is inspected by default.\n " +
        "-t <test_classes_root>...  --  test classes root directories (only these test classes will be run); if not specified, root directory will be searched in project settings \n" +
        "-m <main_classes_root>...  --  main classes root (needed for test classes); if not specified, root directory will be searched in project settings \n" +
        "-v[0|1|2]            --  verbose level. 0 - silent, 1 - verbose, 2 - most verbose. \n" +
        "-profileName         --  name of a profile defined in project \n " +
        "-profilePath         --  absolute path to the profile file";
        System.out.println(help);



        System.exit(1);
    }
}
