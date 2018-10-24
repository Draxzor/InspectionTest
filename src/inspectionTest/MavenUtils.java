package inspectionTest;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.apache.maven.plugins.surefire.report.ReportTestSuite;
import org.apache.maven.plugins.surefire.report.SurefireReportParser;
import org.apache.maven.reporting.MavenReportException;
import org.jetbrains.idea.maven.model.MavenExplicitProfiles;
import org.jetbrains.idea.maven.model.MavenId;
import org.jetbrains.idea.maven.project.*;
import org.jetbrains.idea.maven.utils.MavenUtil;
import org.jetbrains.idea.maven.wizards.MavenProjectBuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

public class MavenUtils {
    private final Map<MavenId, MavenProject> myMavenIdToProjectMapping = new HashMap<>();
    ReentrantReadWriteLock myStructureLock = new ReentrantReadWriteLock();
    Lock myStructureReadLock = myStructureLock.readLock();
    Lock myStructureWriteLock = myStructureLock.writeLock();
    Project myProject;
    VirtualFile myProjectPath;

    public MavenUtils(Project project, VirtualFile projectPath) {
        myProject = project;
        myProjectPath = projectPath;
    }

    private void readLock() {
        myStructureReadLock.lock();
    }

    private void readUnlock() {
        myStructureReadLock.unlock();
    }

    public MavenProject findProject(MavenId id) {
        readLock();
        try {
            return myMavenIdToProjectMapping.get(id);
        }
        finally {
            readUnlock();
        }
    }

    public MavenProjectReaderResult getMavenProject(VirtualFile pomFile) {
        MavenProjectReaderProjectLocator myProjectLocator = new MavenProjectReaderProjectLocator() {
            @Override
            public VirtualFile findProjectFile(MavenId coordinates) {

                MavenProject project = findProject(coordinates);
                return project == null ? null : project.getFile();
            }
        };


        MavenGeneralSettings settings = new MavenProjectBuilder().getGeneralSettings();
        MavenExplicitProfiles s = new MavenExplicitProfiles(Collections.EMPTY_SET, Collections.EMPTY_SET);
        MavenProjectReader reader = new MavenProjectReader(myProject);

        return reader.readProject(settings, pomFile, s, myProjectLocator);
    }



    public List<MavenProjectReaderResult> getAllProjects() {
        List<File> pomFiles = findAllPomFiles(myProjectPath.getPath());
        List<MavenProjectReaderResult> projects = new ArrayList<>();

        for (File f: pomFiles) {
            projects.add(getMavenProject(LocalFileSystem.getInstance().findFileByPath(f.getPath())));
        }

        return projects;
    }

    // not really efficient
    public List<URL> getAllMavenDependecies() {
        List<URL> urls = new ArrayList<>();
        File localRepository = MavenUtil.resolveLocalRepository(null, null, null);
        List<File> allFiles = getAllFilesRecursively(localRepository);

        for (File file : allFiles) {
            if (file.getName().endsWith("jar")) {
                try {
                    urls.add(file.toURI().toURL());
                } catch (MalformedURLException e) {
                    e.printStackTrace();
                }
            }
        }

        return urls;
    }

    private List<File> getAllFilesRecursively(File directory) {
        return getAllFilesRecursively(directory, true);
    }

    // if onlyFiles == true, then no directories will be return and vice versa
    private List<File> getAllFilesRecursively(File directory, boolean onlyFiles) {
        List<File> files = new ArrayList<>();
        List<File> subdirectories = new ArrayList<>();
        File[] filesArray = directory.listFiles();
        if (filesArray != null && filesArray.length != 0) {
            for (File f : filesArray) {
                if (!f.isDirectory() && onlyFiles) {
                    files.add(f);
                }
                if (f.isDirectory()) {
                    subdirectories.add(f);
                    if (!onlyFiles)
                        files.add(f);
                }
            }
        }

        if (!subdirectories.isEmpty()) {
            for (File subdirectory : subdirectories) {
                files.addAll(getAllFilesRecursively(subdirectory, onlyFiles));
            }
        }

        return files;
    }

    public void mavenCompile(String projectPath) throws InterruptedException, IOException {
        try {
            runMavenGoal("clean install -DskipTests=true", projectPath, true);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void runTests(String projectPath) {
        try {
            runMavenGoal("surefire:test", projectPath, true);
            FileDocumentManager.getInstance().saveAllDocuments();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public int getFailures() throws MavenReportException {
        List<File> reportDirs = getAllReportDirectories();
        int failures = 0;
        int testsRun = 0;

        for (File dir : reportDirs) {
            SurefireReportParser parser = new SurefireReportParser(Arrays.asList(dir), Locale.getDefault(), null);
            List<ReportTestSuite> list = parser.parseXMLReportFiles();

            for (ReportTestSuite report : list) {
                failures += report.getNumberOfFailures();
                testsRun += report.getNumberOfTests();
            }
        }
        System.out.println("Tests finished with " + failures + " failures." + " Tests run: " + testsRun);

        return failures;
    }

    private void runMavenGoal(String goal, String projectPath, boolean toLog) throws IOException, InterruptedException {
        String cmd = "mvn " + goal;
        ExecCommand execCommand = new ExecCommand(cmd, projectPath);
    }

    public List<File> findAllPomFiles(String projectPath) {
        List<File> allFiles = getAllFilesRecursively(new File(projectPath));
        List<File> pomFiles = new ArrayList<>();

        for (File f: allFiles) {
            if (f.getName().endsWith("pom.xml"))
                pomFiles.add(f);
        }

        return pomFiles;
    }

    public List<File> getAllReportDirectories() {
        List<File> allFiles = getAllFilesRecursively(new File(myProjectPath.getPath()), false);
        List<File> reportDirs = new ArrayList<>();
        for (File dir: allFiles) {
            if (dir.getName().endsWith("surefire-reports"))
                reportDirs.add(dir);
        }

        return  reportDirs;
    }
}
