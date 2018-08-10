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
import com.intellij.psi.PsiManager;
import org.jdom.JDOMException;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author max
 */
@SuppressWarnings("UseOfSystemOutOrSystemErr")
public class InspectionTestApplication {
    private static final Logger LOG = Logger.getInstance("#com.intellij.codeInspection.InspectionApplication");

    public InspectionToolCmdlineOptionHelpProvider myHelpProvider;
    public String myProjectPath;
    public String mySourceDirectory;
    public String myStubProfile;
    public String myProfileName;
    public String myProfilePath;
    public boolean myRunWithEditorSettings;
    public boolean myRunGlobalToolsOnly;
    private Project myProject;
    private int myVerboseLevel;

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

    private void run() {
        try {
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
            myProject = ProjectUtil.openOrImport(myProjectPath, null, false);

            if (myProject == null) {
                logError("Unable to open project");
                gracefulExit();
                return;
            }

            ApplicationManager.getApplication().runWriteAction(() -> VirtualFileManager.getInstance().refreshWithoutFileWatcher(false));

            PatchProjectUtil.patchProject(myProject);

            logMessageLn(1, InspectionsBundle.message("inspection.done"));
            logMessage(1, InspectionsBundle.message("inspection.application.initializing.project"));

            InspectionProfileImpl inspectionProfile = loadInspectionProfile();
            if (inspectionProfile == null) return;

            final InspectionManagerEx im = (InspectionManagerEx) InspectionManager.getInstance(myProject);

            im.createNewGlobalContext(true).setExternalProfile(inspectionProfile);
            im.setProfile(inspectionProfile.getName());

            if (mySourceDirectory == null) {
                mySourceDirectory = myProjectPath;
            } else {
                mySourceDirectory = mySourceDirectory.replace(File.separatorChar, '/');
            }
            VirtualFile vfsDir = LocalFileSystem.getInstance().findFileByPath(mySourceDirectory);
            if (vfsDir == null) {
                logError(InspectionsBundle.message("inspection.application.directory.cannot.be.found", mySourceDirectory));
                printHelp();
            }
            PsiDirectory psiDirectory = PsiManager.getInstance(myProject).findDirectory(vfsDir);

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
                    for (QuickFix fix : fixes) {
                        if (fix.startInWriteAction()) {
                            WriteCommandAction.runWriteCommandAction(context.getProject(), () -> {
//                                    System.out.println(fix.getName() + " was applied to file " + problem.getPsiElement().getContainingFile().getName());
                                fix.applyFix(context.getProject(), problem);
                            });
                        }
                    }
                }
            }


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
        PsiFile[] files = directory.getFiles();
        if (files != null && files.length != 0) {
            for (PsiFile file : files) {
                for (Tools tool : tools) {
                    problems.addAll(InspectionEngine.runInspectionOnFile(file, tool.getTool(), context));
                }
            }
        }
        PsiDirectory[] subdirectories = directory.getSubdirectories();
        if (subdirectories != null && subdirectories.length != 0) {
            for (PsiDirectory subdirectory : subdirectories) {
                problems.addAll(inspectDirectoryRecursively(tools, context, subdirectory));
            }
        }

        return problems;
    }
}
