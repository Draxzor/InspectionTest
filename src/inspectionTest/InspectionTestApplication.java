package inspectionTest;

import com.intellij.codeInspection.*;
import com.intellij.codeInspection.ex.*;
import com.intellij.conversion.ConversionListener;
import com.intellij.conversion.ConversionService;
import com.intellij.ide.impl.PatchProjectUtil;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.application.*;
import com.intellij.openapi.application.ex.ApplicationEx;
import com.intellij.openapi.application.ex.ApplicationInfoEx;
import com.intellij.openapi.application.ex.ApplicationManagerEx;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.compiler.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
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
import com.intellij.psi.search.scope.packageSet.NamedScope;
import org.apache.commons.io.FileUtils;
import org.apache.maven.plugin.surefire.log.api.ConsoleLogger;
import org.apache.maven.plugins.surefire.report.ReportTestSuite;
import org.apache.maven.plugins.surefire.report.SurefireReportParser;
import org.apache.maven.reporting.MavenReportException;
import org.jdom.JDOMException;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.maven.project.*;
import org.jetbrains.idea.maven.utils.MavenUtil;
import org.jetbrains.idea.maven.wizards.MavenModuleBuilder;

import java.io.*;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.stream.Stream;


@SuppressWarnings("UseOfSystemOutOrSystemErr")
public class InspectionTestApplication {
    private static final Logger LOG = Logger.getInstance("#com.intellij.codeInspection.InspectionApplication");

    private List<VirtualFile> modifiedFiles = new ArrayList<>();
    private int failuresCount;
    public boolean detectTestRoots = true;
    public boolean detectMainRoots = true;
    public boolean isMaven = false;
    private boolean isDefaultProfile = false;
    public InspectionToolCmdlineOptionHelpProvider myHelpProvider;
    String myProjectPath;
//    public String mySourceDirectory;
    public List<String> mySourceDirectories = new ArrayList<>();
    public String myStubProfile;
    public String myProfileName;
    public String myProfilePath;
    public List<String> myTestClassDirectories = new ArrayList<>();
    public List<String> myMainClassDirectories = new ArrayList<>();
    private boolean myRunWithEditorSettings;
    boolean myRunGlobalToolsOnly;
    private Project myProject;
    private int myVerboseLevel;
    private List<ProblemDescriptor> allProblems = new ArrayList<>();
    private GlobalInspectionContextImpl context;
    private MavenUtils mavenUtils;

    public boolean myErrorCodeRequired = true;



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
            try {
                final ApplicationInfoEx appInfo = (ApplicationInfoEx) ApplicationInfo.getInstance();
                logMessage(1, InspectionsBundle.message("inspection.application.starting.up",
                        appInfo.getFullApplicationName() + " (build " + appInfo.getBuild().asString() + ")"));
                application.setSaveAllowed(true);
                logMessageLn(1, InspectionsBundle.message("inspection.done"));

                run();
            } catch (Exception e) {
                LOG.error(e);
            }
    }

    private void printHelp() {
        assert myHelpProvider != null;

        myHelpProvider.printHelpAndExit();
    }


    private void runInspections() throws IOException, JDOMException {
        logMessageLn(1, InspectionsBundle.message("inspection.done"));
        logMessage(1, InspectionsBundle.message("inspection.application.initializing.project"));

        InspectionProfileImpl inspectionProfile = loadInspectionProfile();
        if (inspectionProfile == null) return;

        final InspectionManagerEx im = (InspectionManagerEx) InspectionManager.getInstance(myProject);

        im.createNewGlobalContext(true).setExternalProfile(inspectionProfile);
        im.setProfile(inspectionProfile.getName());

//        List<VirtualFile> vfsDirs = new ArrayList<>();
        List<PsiDirectory> psiDirs = new ArrayList<>();

        for (String dir : mySourceDirectories) {
            VirtualFile vfsDir = LocalFileSystem.getInstance().findFileByPath(dir);
            if (vfsDir == null) {
                logError(InspectionsBundle.message("inspection.application.directory.cannot.be.found", dir));
                printHelp();
            }
            PsiDirectory psiDirectory = PsiManager.getInstance(myProject).findDirectory(vfsDir);
            psiDirs.add(psiDirectory);
        }
        final List<Tools> globalTools = new ArrayList<>();
        final List<Tools> localTools = new ArrayList<>();
        final List<Tools> globalSimpleTools = new ArrayList<>();
        context = im.createNewGlobalContext(true);
        context.initializeTools(globalTools, localTools, globalSimpleTools);
        List<Tools> allTools = new ArrayList<>();
        allTools.addAll(globalTools);
        allTools.addAll(localTools);
        allTools.addAll(globalSimpleTools);

        for (PsiDirectory psiDirectory : psiDirs) {
            allProblems.addAll(inspectDirectoryRecursively(allTools, context, psiDirectory));
        }
    }

    private void applyFixes() {
        for (ProblemDescriptor problem : allProblems) {
            QuickFix[] fixes = problem.getFixes();
            if (fixes != null && fixes.length != 0) {
                QuickFix fix = fixes[0];
//                for (QuickFix fix : fixes) {
                WriteCommandAction.runWriteCommandAction(context.getProject(), () -> {
                    if (problem.getPsiElement() != null && fix != null ) {
                        try {
                            System.out.println("applying " + fix.getName());
                            fix.applyFix(context.getProject(), problem);
                        } catch (Throwable e) {
                            if (fix != null)
                                System.out.println(fix.getName() + " could not be applied");
                        }
                    }

                });

            }
        }
    }


    private void modifyPaths(String subdirectory) {
        myStubProfile = modifyPath(myStubProfile, subdirectory);

        for (int i = 0; i < mySourceDirectories.size(); i++) {
            mySourceDirectories.set(i, modifyPath(mySourceDirectories.get(i), subdirectory));
        }

        for (int i = 0; i < myMainClassDirectories.size(); i++) {
            myMainClassDirectories.set(i, modifyPath(myMainClassDirectories.get(i), subdirectory));
        }
        for (int i = 0; i < myTestClassDirectories.size(); i++) {
            myTestClassDirectories.set(i, modifyPath(myTestClassDirectories.get(i), subdirectory));
        }
        myProjectPath = modifyPath(myProjectPath, subdirectory);
    }

    private String modifyPath(String path, String subdirectory) {
        StringBuffer newPath = new StringBuffer(path);
        return newPath.insert(myProjectPath.length(), "/" + subdirectory).toString();
    }

    private void initProject(String projectPath, Project projectToClose) throws IOException {
        myProjectPath = projectPath;
        myProjectPath = myProjectPath.replace(File.separatorChar, '/');
        ApplicationManager.getApplication().runWriteAction(() -> VirtualFileManager.getInstance().refreshWithoutFileWatcher(false));
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
        ApplicationManagerEx.getApplicationEx().setSaveAllowed(true);

        if (myProject == null) {
            logError("Unable to open project");
            gracefulExit();
            return;
        }


        ApplicationManager.getApplication().runWriteAction(() -> VirtualFileManager.getInstance().refreshWithoutFileWatcher(false));
        PatchProjectUtil.patchProject(myProject);

        // If we call initProject() for the first time
        if (projectToClose == null) {
            if (isMaven) {
                initMavenProject(myProject, vfsProject);

                return;
            }


            if (mySourceDirectories.isEmpty()) {
                mySourceDirectories.add(myProjectPath);

            } else {
                for (int i = 0; i < mySourceDirectories.size(); i++) {
                    mySourceDirectories.set(i, mySourceDirectories.get(i).replace(File.separatorChar, '/'));
                }
            }

            if (detectTestRoots || detectMainRoots) {
                Module[] modules = ModuleManager.getInstance(myProject).getModules();
                for (Module module : modules) {
                    VirtualFile mainClassesRoot = null;
                    VirtualFile testClassesRoot = null;
                    if (detectMainRoots) {
                        mainClassesRoot = CompilerPaths.getModuleOutputDirectory(module, false);
                    }
                    if (detectTestRoots) {
                        testClassesRoot = CompilerPaths.getModuleOutputDirectory(module, true);
                    }
                    if (testClassesRoot != null) {
                        myTestClassDirectories.add(testClassesRoot.getPath());
                    }
                    if (mainClassesRoot != null) {
                        myMainClassDirectories.add(mainClassesRoot.getPath());
                    }
                }
                if (myTestClassDirectories.size() == 0) {
                    throw new IllegalArgumentException("Test output path, specified in project settings is invalid");
                } else if (myMainClassDirectories.size() == 0) {
                    throw new IllegalArgumentException("Main output path, specified in project settings is invalid");
                }
            }
        }
    }

    private void makeCopy() throws IOException {
        String subdirectory = myProject.getName() + "_copy";
        boolean ok = new File(myProjectPath + "/" + subdirectory).mkdirs();
        FileDocumentManager.getInstance().saveAllDocuments();
        FileUtils.copyDirectory(new File(myProjectPath), new File(myProjectPath + "/" + subdirectory));
        modifyPaths(subdirectory);
        initProject(myProjectPath, myProject);
    }

    private void recompileProject() throws IOException {
        if (isMaven) {
            try {
                FileDocumentManager.getInstance().saveAllDocuments();
                mavenUtils.mavenCompile(myProjectPath);
                    FileDocumentManager.getInstance().saveAllDocuments();
                    repeatTests();
                } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                FileUtils.deleteDirectory(new File(myProjectPath));
                FileDocumentManager.getInstance().saveAllDocuments();
                ApplicationManager.getApplication().invokeLater(() -> ApplicationManagerEx.getApplicationEx().exit(true, true), ModalityState.NON_MODAL);
                return;
            }
        }
        CompilerManager compilerManager = CompilerManager.getInstance(myProject);
        compilerManager.compile(compilerManager.createProjectCompileScope(myProject), new CompileStatusNotification() {
                @Override
                public void finished(boolean aborted, int errors, int warnings, CompileContext compileContext) {
                    if (errors > 0) {
                        System.err.println("Compilation finished with errors");
                        CompilerMessage[] messages = compileContext.getMessages(CompilerMessageCategory.ERROR);

                        for (CompilerMessage message : messages) {
                            System.err.println(message.getMessage());
                        }
                    } else if (aborted) {
                        System.err.println("Compilation cancelled");
                    } else {
                        System.out.println("Compilation finished");
                        ApplicationManager.getApplication().invokeLater(() ->  {
                            FileDocumentManager.getInstance().saveAllDocuments();
                            repeatTests();
                        }, ModalityState.NON_MODAL);
                    }
                    ApplicationManager.getApplication().invokeLater(() -> ApplicationManagerEx.getApplicationEx().exit(true, true), ModalityState.NON_MODAL);
                }
            });
    }

    private void run() {
        try {
        if (new File(myProjectPath + "_copy").exists()) {
            FileUtils.deleteDirectory(new File(myProjectPath + "_copy"));
            FileDocumentManager.getInstance().saveAllDocuments();

        }
        initProject(myProjectPath, null);
        JUnitRunner runner = null;
        if (isMaven) {
            mavenUtils.runTests(myProjectPath);
            failuresCount = mavenUtils.getFailures();
        } else {
            runner = runTests();
        }
        makeCopy();
        ReadAction.run(() -> runInspections());
        applyFixes();
        if (!isMaven)
            failuresCount = runner.getFailuresCount();
        recompileProject();
        } catch (Throwable e) {
            LOG.error(e);
            logError(e.getMessage());
            gracefulExit();
        }
    }

    private void repeatTests() {
        int newFailruesCount = 0;
        if (isMaven) {
            try {
                mavenUtils.runTests(myProjectPath);
                newFailruesCount = mavenUtils.getFailures();
            } catch (MavenReportException e) {
                e.printStackTrace();
            }
        } else {
            JUnitRunner newRunner = null;
            try {
                newRunner = runTests();
                newFailruesCount = newRunner.getFailuresCount();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

         if (newFailruesCount > failuresCount)  {
            System.out.println("Invalid inspection(s) detected!");
        } else {
            System.out.println("Inspections are correct.");
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
        }
        URL url = this.getClass().getResource("/META-INF/defaultProfile.xml");
        inspectionProfile = loadProfileByPath(url.getPath());
        logError("Using default project profile");

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
                    List<ProblemDescriptor> list = new ArrayList<>();
                    try {
                        list = InspectionEngine.runInspectionOnFile(file, tool.getTool(), context);
                    } catch (Throwable e) {

                    }
//                    System.out.println(tool.getTool().getShortName() + " to " + file.getName());
                    problems.addAll(list);
                }
            }
        }

        return problems;
    }

    private List<PsiFile> getAllPsiFiles(PsiDirectory directory) {
        List<PsiFile> files = new ArrayList<>();
        PsiFile[] filesArray = directory.getFiles();
        if (filesArray != null && filesArray.length != 0) {
            files.addAll(Arrays.asList(filesArray));
        }
        PsiDirectory[] subdirectories = directory.getSubdirectories();
        if (subdirectories != null && subdirectories.length != 0) {
            for (PsiDirectory subdirectory : subdirectories) {
                files.addAll(getAllPsiFiles(subdirectory));
            }
        }

        return files;
    }

    private JUnitRunner runTests() throws MalformedURLException, NoSuchFieldException, IllegalAccessException {
        ClassLoader pluginCL = JUnitRunner.class.getClassLoader();
        Class c = pluginCL.getClass().getSuperclass();
        Field f = c.getDeclaredField("myURLs");
        f.setAccessible(true);
        List<URL> myURLs = new ArrayList<>((ArrayList<URL>) f.get(pluginCL));
        List<PsiFile> psiTestFiles = new ArrayList<>();

        for (String testClassDirectory : myTestClassDirectories) {
            myURLs.add(new File(testClassDirectory).toURI().toURL());
            PsiDirectory testPsiDirectory = PsiManager.getInstance(myProject).findDirectory(LocalFileSystem.getInstance().findFileByPath(testClassDirectory));
            psiTestFiles.addAll(getAllPsiFiles(testPsiDirectory));
        }
        List<String> classNames = new ArrayList<>();

        for (PsiFile psiTestFile : psiTestFiles) {
            if (psiTestFile instanceof PsiJavaFile &&
                psiTestFile.getVirtualFile().getNameWithoutExtension().contains("Test")) {
                String packageName = ((PsiJavaFile) psiTestFile).getPackageName();
                if (packageName != "") packageName += ".";
                String fullName = packageName + psiTestFile.getVirtualFile().getNameWithoutExtension();
                classNames.add(fullName);
            } else {
                myURLs.add(new File(psiTestFile.getVirtualFile().getPath()).toURI().toURL());
            }
        }
        JUnitRunner runner = new JUnitRunner();

        for (String mainClassDirectory : myMainClassDirectories) {
            myURLs.add(new File(mainClassDirectory).toURI().toURL());
        }
        ClassLoader contextCL = Thread.currentThread().getContextClassLoader();
        f = contextCL.getClass().getDeclaredField("myURLs");
        f.setAccessible(true);
        myURLs.addAll((ArrayList<URL>) f.get(contextCL));
        runner.addURLs(myURLs);
        runner.setTestClassNames(classNames);
        try {
            runner.startThread();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return runner;
    }

    private void initMavenProject(Project project, VirtualFile projectPath) {
        mavenUtils = new MavenUtils(project, projectPath);

        List<MavenProjectReaderResult> projects = mavenUtils.getAllProjects();
        mySourceDirectories.clear();

        for (MavenProjectReaderResult mavenProject : projects) {
            List<String> sourceDirectories = mavenProject.mavenModel.getBuild().getSources();
            for (String dir : sourceDirectories) {
                if (new File(dir).exists()) {
                    mySourceDirectories.add(dir);
                }
            }
        }

        try {
            mavenUtils.mavenCompile(myProjectPath);
        } catch (InterruptedException | IOException e) {
            e.printStackTrace();
        }
    }
}
