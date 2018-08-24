// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package inspectionTest;

import com.intellij.codeInspection.*;
import com.intellij.codeInspection.ex.*;
import com.intellij.conversion.ConversionListener;
import com.intellij.conversion.ConversionService;
import com.intellij.ide.impl.PatchProjectUtil;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ex.ApplicationEx;
import com.intellij.openapi.application.ex.ApplicationInfoEx;
import com.intellij.openapi.application.ex.ApplicationManagerEx;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.task.ProjectTaskManager;
import com.intellij.task.ProjectTaskNotification;
import com.intellij.task.ProjectTaskResult;
import org.apache.commons.io.FileUtils;
import org.jdom.JDOMException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;


@SuppressWarnings("UseOfSystemOutOrSystemErr")
public class InspectionTestApplication {
    private static final Logger LOG = Logger.getInstance("#com.intellij.codeInspection.InspectionApplication");
    private boolean isFirstRun = true;

    private AtomicInteger testFailureCount;
    public InspectionToolCmdlineOptionHelpProvider myHelpProvider;
    public String myProjectPath;
    public String mySourceDirectory;
    public String myStubProfile;
    public String myProfileName;
    public String myProfilePath;
    public String myTestClassDirectory;
    public String myMainClassDirectory;
    public boolean myRunWithEditorSettings;
    public boolean myRunGlobalToolsOnly;
    private Project myProject;
    private int myVerboseLevel;

    public boolean myErrorCodeRequired = true;

    public synchronized void proceedTestResult(int failureCount) {
        if (isFirstRun) {
            testFailureCount = new AtomicInteger(failureCount);
            isFirstRun = false;

        } else {

        }
    }

    public void startup() {
        if (myProjectPath == null) {
            logError("Project to inspect is not defined");
            printHelp();
        }

        if (myProfileName == null && myProfilePath == null && myStubProfile == null) {
            logError("Profile to inspect with is not defined");
            printHelp();
        }

        final ApplicationEx application = ApplicationManagerEx.getApplicationEx();
        application.runReadAction(() -> {
            try {
                final ApplicationInfoEx appInfo = (ApplicationInfoEx) ApplicationInfo.getInstance();
                logMessage(1, InspectionsBundle.message("inspection.application.starting.up",
                        appInfo.getFullApplicationName() + " (build " + appInfo.getBuild().asString() + ")"));
                application.setSaveAllowed(false);
                logMessageLn(1, InspectionsBundle.message("inspection.done"));

                run();
            } catch (Exception e) {
                LOG.error(e);
            } finally {
                if (myErrorCodeRequired) application.exit(true, true);
            }
        });
    }

    private void printHelp() {
        assert myHelpProvider != null;

        myHelpProvider.printHelpAndExit();
    }

    private void rebuildProject() {
        ApplicationEx application= ApplicationManagerEx.getApplicationEx();
        application.runWriteAction(() -> {
            ProjectTaskManager.getInstance(myProject).rebuildAllModules(new ProjectTaskNotification() {
                @Override
                public void finished(@NotNull ProjectTaskResult executionResult) {
                    System.out.println("done rebuilding");
                    application.invokeLater(() -> application.exit(true, true));
                }
            });
        });
    }

    private void runAndApplyInspections(InspectionManagerEx im, PsiDirectory psiDirectory) {
        final List<Tools> globalTools = new ArrayList<>();
        final List<Tools> localTools = new ArrayList<>();
        final List<Tools> globalSimpleTools = new ArrayList<>();
        GlobalInspectionContextImpl context = im.createNewGlobalContext(true);
        context.initializeTools(globalTools, localTools, globalSimpleTools);
        List<Tools> allTools = new ArrayList<>();
        allTools.addAll(globalTools);
        allTools.addAll(localTools);
        allTools.addAll(globalSimpleTools);
        List<ProblemDescriptor> allProblems = inspectDirectoryRecursively(allTools, context, psiDirectory);

        for (ProblemDescriptor problem : allProblems) {
            QuickFix[] fixes = problem.getFixes();
            if (fixes != null && fixes.length != 0) {
                QuickFix fix = fixes[0];
//                for (QuickFix fix : fixes) {
//                    if (fix.startInWriteAction()) {
                WriteCommandAction.runWriteCommandAction(context.getProject(), () -> {
                    try {
                        System.out.println("applying " + fix.getName());
                        fix.applyFix(context.getProject(), problem);
                    } catch (Exception e) {
                        System.out.println(fix.getName() + " could not be applied");
                    }

                });

            }
        }
    }


    private void modifyPaths(String subdirectory) {
        myStubProfile = modifyPath(myStubProfile, subdirectory);
        mySourceDirectory = modifyPath(mySourceDirectory, subdirectory);
        myTestClassDirectory = modifyPath(myTestClassDirectory, subdirectory);
        myMainClassDirectory = modifyPath(myMainClassDirectory, subdirectory);
        myProjectPath = modifyPath(myProjectPath, subdirectory);
    }

    private String modifyPath(String path, String subdirectory) {
        StringBuffer newPath = new StringBuffer(path);
        return newPath.insert(myProjectPath.length(), "/" + subdirectory).toString();
    }


    private void initProject(String projectPath, Project projectToClose) {
        myProjectPath = projectPath;
        myProjectPath = myProjectPath.replace(File.separatorChar, '/');
        VirtualFile vfsProject = LocalFileSystem.getInstance().findFileByPath(myProjectPath);
        if (vfsProject == null) {
            logError(InspectionsBundle.message("inspection.application.file.cannot.be.found", myProjectPath));
            printHelp();
        }

        logMessage(1, InspectionsBundle.message("inspection.application.opening.project"));
        final ConversionService conversionService = ConversionService.getInstance();
        if (conversionService.convertSilently(myProjectPath, createConversionListener()).openingIsCanceled()) {
            gracefulExit();
            return;
        }
        if (projectToClose != null)
            ProjectUtil.closeAndDispose(projectToClose);
        myProject = ProjectUtil.openOrImport(myProjectPath, null, false);

        if (myProject == null) {
            logError("Unable to open project");
            gracefulExit();
            return;
        }
        ApplicationManager.getApplication().runWriteAction(() -> VirtualFileManager.getInstance().refreshWithoutFileWatcher(false));
        PatchProjectUtil.patchProject(myProject);

        if (mySourceDirectory == null) {
            mySourceDirectory = myProjectPath;
        } else {
            mySourceDirectory = mySourceDirectory.replace(File.separatorChar, '/');
        }
    }

    private void run() {
        try {
            initProject(myProjectPath, null);

            Thread testThread = runTests();

            String subdirectory = myProject.getName() + "_copy";
            FileUtils.copyDirectory(new File(myProjectPath), new File(myProjectPath + "/" + subdirectory));
            modifyPaths(subdirectory);
            initProject(myProjectPath, myProject);

            logMessageLn(1, InspectionsBundle.message("inspection.done"));
            logMessage(1, InspectionsBundle.message("inspection.application.initializing.project"));

            InspectionProfileImpl inspectionProfile = loadInspectionProfile();
            if (inspectionProfile == null) return;

            final InspectionManagerEx im = (InspectionManagerEx) InspectionManager.getInstance(myProject);

            im.createNewGlobalContext(true).setExternalProfile(inspectionProfile);
            im.setProfile(inspectionProfile.getName());

            VirtualFile vfsDir = LocalFileSystem.getInstance().findFileByPath(mySourceDirectory);
            if (vfsDir == null) {
                logError(InspectionsBundle.message("inspection.application.directory.cannot.be.found", mySourceDirectory));
                printHelp();
            }
            PsiDirectory psiDirectory = PsiManager.getInstance(myProject).findDirectory(vfsDir);
            
            runAndApplyInspections(im, psiDirectory);
            rebuildProject();
            testThread.join();
            System.out.println(testFailureCount.get());

            System.out.println("OK");
        } catch (IOException e) {
            LOG.error(e);
            logError(e.getMessage());
            printHelp();
        } catch (Throwable e) {
            LOG.error(e);
            logError(e.getMessage());
            gracefulExit();
        }
    }

    private void gracefulExit() {
        if (myErrorCodeRequired) {
            System.exit(1);
        } else {
            closeProject();
            throw new RuntimeException("Failed to proceed");
        }
    }

    private void closeProject() {
        if (myProject != null && !myProject.isDisposed()) {
            ProjectUtil.closeAndDispose(myProject);
            myProject = null;
        }
    }

    @Nullable
    private InspectionProfileImpl loadInspectionProfile() throws IOException, JDOMException {
        InspectionProfileImpl inspectionProfile = null;

        //fetch profile by name from project file (project profiles can be disabled)
        if (myProfileName != null) {
            inspectionProfile = loadProfileByName(myProfileName);
            if (inspectionProfile == null) {
                logError("Profile with configured name (" + myProfileName + ") was not found (neither in project nor in config directory)");
                gracefulExit();
                return null;
            }
            return inspectionProfile;
        }

        if (myProfilePath != null) {
            inspectionProfile = loadProfileByPath(myProfilePath);
            if (inspectionProfile == null) {
                logError("Failed to load profile from \'" + myProfilePath + "\'");
                gracefulExit();
                return null;
            }
            return inspectionProfile;
        }

        if (myStubProfile != null) {
            if (!myRunWithEditorSettings) {
                inspectionProfile = loadProfileByName(myStubProfile);
                if (inspectionProfile != null) return inspectionProfile;

                inspectionProfile = loadProfileByPath(myStubProfile);
                if (inspectionProfile != null) return inspectionProfile;
            }

            inspectionProfile = InspectionProjectProfileManager.getInstance(myProject).getCurrentProfile();
            logError("Using default project profile");
        }
        return inspectionProfile;
    }

    @Nullable
    private InspectionProfileImpl loadProfileByPath(final String profilePath) throws IOException, JDOMException {
        InspectionProfileImpl inspectionProfile = ApplicationInspectionProfileManager.getInstanceImpl().loadProfile(profilePath);
        if (inspectionProfile != null) {
            logMessageLn(1, "Loaded profile \'" + inspectionProfile.getName() + "\' from file \'" + profilePath + "\'");
        }
        return inspectionProfile;
    }

    @Nullable
    private InspectionProfileImpl loadProfileByName(final String profileName) {
        InspectionProfileImpl inspectionProfile = InspectionProjectProfileManager.getInstance(myProject).getProfile(profileName, false);
        if (inspectionProfile != null) {
            logMessageLn(1, "Loaded shared project profile \'" + profileName + "\'");
        } else {
            //check if ide profile is used for project
            for (InspectionProfileImpl profile : InspectionProjectProfileManager.getInstance(myProject).getProfiles()) {
                if (Comparing.strEqual(profile.getName(), profileName)) {
                    inspectionProfile = profile;
                    logMessageLn(1, "Loaded local profile \'" + profileName + "\'");
                    break;
                }
            }
        }

        return inspectionProfile;
    }

    private ConversionListener createConversionListener() {
        return new ConversionListener() {
            @Override
            public void conversionNeeded() {
                logMessageLn(1, InspectionsBundle.message("inspection.application.project.has.older.format.and.will.be.converted"));
            }

            @Override
            public void successfullyConverted(final File backupDir) {
                logMessageLn(1, InspectionsBundle.message(
                        "inspection.application.project.was.succesfully.converted.old.project.files.were.saved.to.0",
                        backupDir.getAbsolutePath()));
            }

            @Override
            public void error(final String message) {
                logError(InspectionsBundle.message("inspection.application.cannot.convert.project.0", message));
            }

            @Override
            public void cannotWriteToFiles(final List<File> readonlyFiles) {
                StringBuilder files = new StringBuilder();
                for (File file : readonlyFiles) {
                    files.append(file.getAbsolutePath()).append("; ");
                }
                logError(InspectionsBundle.message("inspection.application.cannot.convert.the.project.the.following.files.are.read.only.0", files.toString()));
            }
        };
    }

    public void setVerboseLevel(int verboseLevel) {
        myVerboseLevel = verboseLevel;
    }

    private void logMessage(int minVerboseLevel, String message) {
        if (myVerboseLevel >= minVerboseLevel) {
            System.out.print(message);
        }
    }

    private static void logError(String message) {
        System.err.println(message);
    }

    private void logMessageLn(int minVerboseLevel, String message) {
        if (myVerboseLevel >= minVerboseLevel) {
            System.out.println(message);
        }
    }


    private List<ProblemDescriptor> inspectDirectoryRecursively(List<Tools> tools, GlobalInspectionContextImpl context, PsiDirectory directory) {
        List<ProblemDescriptor> problems = new ArrayList<>();
        List<PsiFile> allFiles = getAllPsiFiles(directory);
        if (allFiles.size() != 0) {
            for (PsiFile file : allFiles) {
                for (Tools tool : tools) {
//                    System.out.println(tool.getTool().getShortName() + " to " + file.getName());
                    problems.addAll(InspectionEngine.runInspectionOnFile(file, tool.getTool(), context));
                }
            }
        }

        return problems;
    }

    private List<PsiFile> getAllPsiFiles(PsiDirectory directory) {
        List<PsiFile> files = new ArrayList<>();
        PsiFile[] filesArray = directory.getFiles();
        if (filesArray.length != 0) {
            files.addAll(Arrays.asList(filesArray));
        }
        PsiDirectory[] subdirectories = directory.getSubdirectories();
        if (subdirectories.length != 0) {
            for (PsiDirectory subdirectory : subdirectories) {
                files.addAll(getAllPsiFiles(subdirectory));
            }
        }

        return files;
    }

    private Thread runTests() throws MalformedURLException, NoSuchFieldException, IllegalAccessException, ClassNotFoundException, NoSuchMethodException, InstantiationException, InvocationTargetException {
        VirtualFile vfsTestDir = LocalFileSystem.getInstance().findFileByPath(myTestClassDirectory);
        PsiDirectory testPsiDirectory = PsiManager.getInstance(myProject).findDirectory(vfsTestDir);
        List<PsiFile> psiTestFiles = getAllPsiFiles(testPsiDirectory);
        List<String> classNames = new ArrayList<>();

        for (PsiFile psiTestFile : psiTestFiles) {
            String packageName = ((PsiJavaFile)psiTestFile).getPackageName();
            if (packageName != "") packageName += ".";
            String fullName =  packageName + psiTestFile.getVirtualFile().getNameWithoutExtension();
            classNames.add(fullName);
        }

        JUnitRunner runner = new JUnitRunner();
        URL test = new File(myTestClassDirectory).toURI().toURL();
        URL main = new File(myMainClassDirectory).toURI().toURL();
        URL u = new URL("file:/home/jetbrains/.IntelliJIdea2018.2/system/plugins-sandbox/plugins/inspection-test/classes/");
//        JUnitThread.class.
        ClassLoader contextCL = Thread.currentThread().getContextClassLoader();
        Field f = contextCL.getClass().getDeclaredField("myURLs");
        f.setAccessible(true);
        List<URL> myURLs = (ArrayList<URL>) f.get(contextCL);

        runner.addURLs(myURLs);
//        runner.addURL(u);
        runner.addURL(test);
        runner.addURL(main);
        runner.setTestClassNames(classNames);
        return runner.doWork(this);
    }
    @Nullable
    private static String getPrefix(final String text) {
        //noinspection HardCodedStringLiteral
        int idx = text.indexOf(" in ");
        if (idx == -1) {
            //noinspection HardCodedStringLiteral
            idx = text.indexOf(" of ");
        }

        return idx == -1 ? null : text.substring(0, idx);
    }
}
