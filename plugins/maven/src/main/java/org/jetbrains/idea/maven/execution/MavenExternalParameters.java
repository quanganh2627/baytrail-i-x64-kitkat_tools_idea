/* ==========================================================================
 * Copyright 2006 Mevenide Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * =========================================================================
 */


package org.jetbrains.idea.maven.execution;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.RunManager;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.execution.impl.EditConfigurationsDialog;
import com.intellij.execution.impl.RunManagerImpl;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.impl.JavaAwareProjectJdkTableImpl;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.roots.ui.configuration.ProjectSettingsService;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.encoding.EncodingManager;
import com.intellij.openapi.vfs.encoding.EncodingProjectManager;
import com.intellij.util.PathUtil;
import com.intellij.util.io.ZipUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.maven.artifactResolver.MavenArtifactResolvedM2RtMarker;
import org.jetbrains.idea.maven.artifactResolver.MavenArtifactResolvedM3RtMarker;
import org.jetbrains.idea.maven.artifactResolver.common.MavenModuleMap;
import org.jetbrains.idea.maven.project.MavenGeneralSettings;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.idea.maven.project.MavenProjectsManager;
import org.jetbrains.idea.maven.utils.MavenSettings;
import org.jetbrains.idea.maven.utils.MavenUtil;

import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import java.io.*;
import java.util.*;
import java.util.zip.ZipOutputStream;

/**
 * @author Ralf Quebbemann
 */
public class MavenExternalParameters {

  private static final Logger LOG = Logger.getInstance(MavenExternalParameters.class);

  public static final String MAVEN_LAUNCHER_CLASS = "org.codehaus.classworlds.Launcher";
  @NonNls private static final String JAVA_HOME = "JAVA_HOME";
  @NonNls private static final String MAVEN_OPTS = "MAVEN_OPTS";

  @Deprecated // Use createJavaParameters(Project,MavenRunnerParameters, MavenGeneralSettings,MavenRunnerSettings,MavenRunConfiguration)
  public static JavaParameters createJavaParameters(@Nullable final Project project,
                                                    @NotNull final MavenRunnerParameters parameters,
                                                    @Nullable MavenGeneralSettings coreSettings,
                                                    @Nullable MavenRunnerSettings runnerSettings) throws ExecutionException {
    return createJavaParameters(project, parameters, coreSettings, runnerSettings, null);
  }

  public static JavaParameters createJavaParameters(@Nullable final Project project,
                                                    @NotNull final MavenRunnerParameters parameters) throws ExecutionException {
    return createJavaParameters(project, parameters, null, null, null);
  }

  /**
   *
   * @param project
   * @param parameters
   * @param coreSettings
   * @param runnerSettings
   * @param runConfiguration used to creation fix if maven home not found
   * @return
   * @throws ExecutionException
   */
  public static JavaParameters createJavaParameters(@Nullable final Project project,
                                                    @NotNull final MavenRunnerParameters parameters,
                                                    @Nullable MavenGeneralSettings coreSettings,
                                                    @Nullable MavenRunnerSettings runnerSettings,
                                                    @Nullable MavenRunConfiguration runConfiguration) throws ExecutionException {
    final JavaParameters params = new JavaParameters();

    ApplicationManager.getApplication().assertReadAccessAllowed();

    if (coreSettings == null) {
      coreSettings = project == null ? new MavenGeneralSettings() : MavenProjectsManager.getInstance(project).getGeneralSettings();
    }
    if (runnerSettings == null) {
      runnerSettings = project == null ? new MavenRunnerSettings() : MavenRunner.getInstance(project).getState();
    }

    params.setWorkingDirectory(parameters.getWorkingDirFile());

    params.setJdk(getJdk(project, runnerSettings, project != null && MavenRunner.getInstance(project).getState() == runnerSettings));

    final String mavenHome = resolveMavenHome(coreSettings, project, runConfiguration);

    addVMParameters(params.getVMParametersList(), mavenHome, runnerSettings);

    File confFile = MavenUtil.getMavenConfFile(new File(mavenHome));
    if (!confFile.isFile()) {
      throw new ExecutionException("Configuration file is not exists in maven home: " + confFile.getAbsolutePath());
    }

    if (project != null && parameters.isResolveToWorkspace()) {
      try {
        String resolverJar = getArtifactResolverJar(MavenUtil.isMaven3(mavenHome));
        confFile = patchConfFile(confFile, resolverJar);

        File modulesPathsFile = dumpModulesPaths(project);
        params.getVMParametersList().addProperty(MavenModuleMap.PATHS_FILE_PROPERTY, modulesPathsFile.getAbsolutePath());
      }
      catch (IOException e) {
        LOG.error(e);
        throw new ExecutionException("Failed to run maven configuration", e);
      }
    }

    params.getVMParametersList().addProperty("classworlds.conf", confFile.getPath());

    for (String path : getMavenClasspathEntries(mavenHome)) {
      params.getClassPath().add(path);
    }

    params.setMainClass(MAVEN_LAUNCHER_CLASS);
    EncodingManager encodingManager = project == null
                                      ? EncodingProjectManager.getInstance()
                                      : EncodingProjectManager.getInstance(project);
    params.setCharset(encodingManager.getDefaultCharset());

    addMavenParameters(params.getProgramParametersList(), mavenHome, coreSettings, runnerSettings, parameters);

    return params;
  }

  private static File patchConfFile(File conf, String library) throws IOException {
    File tmpConf = File.createTempFile("idea-", "-mvn.conf");
    tmpConf.deleteOnExit();
    patchConfFile(conf, tmpConf, library);

    return tmpConf;
  }

  private static void patchConfFile(File originalConf, File dest, String library) throws IOException {
    Scanner sc = new Scanner(originalConf);

    try {
      BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(dest)));

      try {
        boolean patched = false;

        while (sc.hasNextLine()) {
          String line = sc.nextLine();

          out.append(line);
          out.newLine();

          if (!patched && "[plexus.core]".equals(line)) {
            out.append("load ").append(library);
            out.newLine();

            patched = true;
          }
        }
      }
      finally {
        out.close();
      }
    }
    finally {
      sc.close();
    }

  }

  private static String getArtifactResolverJar(boolean isMaven3) throws IOException {
    Class marker = isMaven3 ? MavenArtifactResolvedM3RtMarker.class : MavenArtifactResolvedM2RtMarker.class;

    File classDirOrJar = new File(PathUtil.getJarPathForClass(marker));

    if (!classDirOrJar.isDirectory()) {
      return classDirOrJar.getAbsolutePath(); // it's a jar in IDEA installation.
    }

    // it's a classes directory, we are in development mode.
    File tempFile = FileUtil.createTempFile("idea-", "-artifactResolver.jar");
    tempFile.deleteOnExit();

    ZipOutputStream zipOutput = new ZipOutputStream(new FileOutputStream(tempFile));
    try {
      ZipUtil.addDirToZipRecursively(zipOutput, null, classDirOrJar, "", null, null);

      if (isMaven3) {
        File m2Module = new File(PathUtil.getJarPathForClass(MavenModuleMap.class));

        String commonClassesPath = MavenModuleMap.class.getPackage().getName().replace('.', '/');
        ZipUtil.addDirToZipRecursively(zipOutput, null, new File(m2Module, commonClassesPath), commonClassesPath, null, null);
      }
    } finally {
      zipOutput.close();
    }

    return tempFile.getAbsolutePath();
  }

  private static File dumpModulesPaths(@NotNull Project project) throws IOException {
    ApplicationManager.getApplication().assertReadAccessAllowed();

    Properties res = new Properties();

    MavenProjectsManager manager = MavenProjectsManager.getInstance(project);

    for (Module module : ModuleManager.getInstance(project).getModules()) {
      if (manager.isMavenizedModule(module)) {
        MavenProject mavenProject = manager.findProject(module);
        if (mavenProject != null && !manager.isIgnored(mavenProject)) {
          res.setProperty(mavenProject.getMavenId().getGroupId()
                          + ':' + mavenProject.getMavenId().getArtifactId()
                          + ":pom"
                          + ':' + mavenProject.getMavenId().getVersion(),
                          mavenProject.getFile().getPath());

          res.setProperty(mavenProject.getMavenId().getGroupId()
                          + ':' + mavenProject.getMavenId().getArtifactId()
                          + ':' + mavenProject.getPackaging()
                          + ':' + mavenProject.getMavenId().getVersion(),
                           mavenProject.getOutputDirectory());

        }
      }
    }

    File file = new File(PathManager.getSystemPath(), "Maven/idea-projects-state-" + project.getLocationHash() + ".properties");
    file.getParentFile().mkdirs();

    OutputStream out = new BufferedOutputStream(new FileOutputStream(file));
    try {
      res.store(out, null);
    }
    finally {
      out.close();
    }

    return file;
  }

  @NotNull
  private static Sdk getJdk(@Nullable Project project, MavenRunnerSettings runnerSettings, boolean isGlobalRunnerSettings) throws ExecutionException {
    String name = runnerSettings.getJreName();
    if (name.equals(MavenRunnerSettings.USE_INTERNAL_JAVA)) {
      return JavaAwareProjectJdkTableImpl.getInstanceEx().getInternalJdk();
    }

    if (name.equals(MavenRunnerSettings.USE_PROJECT_JDK)) {
      if (project != null) {
        Sdk res = ProjectRootManager.getInstance(project).getProjectSdk();
        if (res != null) {
          return res;
        }
      }

      if (project == null) {
        Sdk recent = ProjectJdkTable.getInstance().findMostRecentSdkOfType(JavaSdk.getInstance());
        if (recent != null) return recent;
        return JavaAwareProjectJdkTableImpl.getInstanceEx().getInternalJdk();
      }

      throw new ProjectJdkSettingsOpenerExecutionException("Project JDK is not specified. <a href='#'>Configure</a>", project);
    }

    if (name.equals(MavenRunnerSettings.USE_JAVA_HOME)) {
      final String javaHome = System.getenv(JAVA_HOME);
      if (StringUtil.isEmptyOrSpaces(javaHome)) {
        throw new ExecutionException(RunnerBundle.message("maven.java.home.undefined"));
      }
      final Sdk jdk = JavaSdk.getInstance().createJdk("", javaHome);
      if (jdk == null) {
        throw new ExecutionException(RunnerBundle.message("maven.java.home.invalid", javaHome));
      }
      return jdk;
    }

    for (Sdk projectJdk : ProjectJdkTable.getInstance().getAllJdks()) {
      if (projectJdk.getName().equals(name)) {
        return projectJdk;
      }
    }

    if (isGlobalRunnerSettings) {
      throw new ExecutionException(RunnerBundle.message("maven.java.not.found.default.config", name));
    }
    else {
      throw new ExecutionException(RunnerBundle.message("maven.java.not.found", name));
    }
  }

  public static void addVMParameters(ParametersList parametersList, String mavenHome, MavenRunnerSettings runnerSettings) {
    parametersList.addParametersString(System.getenv(MAVEN_OPTS));

    parametersList.addParametersString(runnerSettings.getVmOptions());

    parametersList.addProperty("maven.home", mavenHome);
  }

  private static void addMavenParameters(ParametersList parametersList,
                                         String mavenHome,
                                         MavenGeneralSettings coreSettings,
                                         MavenRunnerSettings runnerSettings,
                                         MavenRunnerParameters parameters) {
    encodeCoreAndRunnerSettings(coreSettings, mavenHome, parametersList);

    if (runnerSettings.isSkipTests()) {
      parametersList.addProperty("skipTests", "true");
    }

    for (Map.Entry<String, String> entry : runnerSettings.getMavenProperties().entrySet()) {
      if (entry.getKey().length() > 0) {
        parametersList.addProperty(entry.getKey(), entry.getValue());
      }
    }

    for (String goal : parameters.getGoals()) {
      parametersList.add(goal);
    }

    addOption(parametersList, "P", encodeProfiles(parameters.getProfilesMap()));
  }

  private static void addOption(ParametersList cmdList, @NonNls String key, @NonNls String value) {
    if (!StringUtil.isEmptyOrSpaces(value)) {
      cmdList.add("-" + key);
      cmdList.add(value);
    }
  }

  public static String resolveMavenHome(@NotNull MavenGeneralSettings coreSettings) throws ExecutionException {
    return resolveMavenHome(coreSettings, null, null);
  }

  /**
   *
   * @param coreSettings
   * @param project used to creation fix if maven home not found
   * @param runConfiguration used to creation fix if maven home not found
   * @return
   * @throws ExecutionException
   */
  public static String resolveMavenHome(@NotNull MavenGeneralSettings coreSettings,
                                        @Nullable Project project,
                                        @Nullable MavenRunConfiguration runConfiguration) throws ExecutionException {
    final File file = MavenUtil.resolveMavenHomeDirectory(coreSettings.getMavenHome());

    if (file == null) {
      throw createExecutionException(RunnerBundle.message("external.maven.home.no.default"),
                                     RunnerBundle.message("external.maven.home.no.default.with.fix"),
                                     coreSettings, project, runConfiguration);
    }

    if (!file.exists()) {
      throw createExecutionException(RunnerBundle.message("external.maven.home.does.not.exist", file.getPath()),
                                     RunnerBundle.message("external.maven.home.does.not.exist.with.fix", file.getPath()),
                                     coreSettings, project, runConfiguration);
    }

    if (!MavenUtil.isValidMavenHome(file)) {
      throw createExecutionException(RunnerBundle.message("external.maven.home.invalid", file.getPath()),
                                     RunnerBundle.message("external.maven.home.invalid.with.fix", file.getPath()),
                                     coreSettings, project, runConfiguration);
    }

    try {
      return file.getCanonicalPath();
    }
    catch (IOException e) {
      throw new ExecutionException(e.getMessage(), e);
    }
  }

  private static ExecutionException createExecutionException(String text,
                                                             String textWithFix,
                                                             @NotNull MavenGeneralSettings coreSettings,
                                                             @Nullable Project project,
                                                             @Nullable MavenRunConfiguration runConfiguration) {
    Project notNullProject = project;
    if (notNullProject == null) {
      if (runConfiguration == null) return new ExecutionException(text);
      notNullProject = runConfiguration.getProject();
      if (notNullProject == null) return new ExecutionException(text);
    }

    if (coreSettings == MavenProjectsManager.getInstance(notNullProject).getGeneralSettings()) {
      return new ProjectSettingsOpenerExecutionException(textWithFix, notNullProject);
    }

    if (runConfiguration != null) {
      Project runCfgProject = runConfiguration.getProject();
      if (runCfgProject != null) {
        if (((RunManagerImpl)RunManager.getInstance(runCfgProject)).getSettings(runConfiguration) != null) {
          return new RunConfigurationOpenerExecutionException(textWithFix, runConfiguration);
        }
      }
    }

    return new ExecutionException(text);
  }

  @SuppressWarnings({"HardCodedStringLiteral"})
  private static List<String> getMavenClasspathEntries(final String mavenHome) {
    File mavenHomeBootAsFile = new File(new File(mavenHome, "core"), "boot");
    // if the dir "core/boot" does not exist we are using a Maven version > 2.0.5
    // in this case the classpath must be constructed from the dir "boot"
    if (!mavenHomeBootAsFile.exists()) {
      mavenHomeBootAsFile = new File(mavenHome, "boot");
    }

    List<String> classpathEntries = new ArrayList<String>();

    File[] files = mavenHomeBootAsFile.listFiles();
    if (files != null) {
      for (File file : files) {
        if (file.getName().contains("classworlds")) {
          classpathEntries.add(file.getAbsolutePath());
        }
      }
    }

    return classpathEntries;
  }

  private static void encodeCoreAndRunnerSettings(MavenGeneralSettings coreSettings, String mavenHome,
                                                  ParametersList cmdList) {
    if (coreSettings.isWorkOffline()) {
      cmdList.add("--offline");
    }

    boolean atLeastMaven3 = MavenUtil.isMaven3(mavenHome);

    if (!atLeastMaven3) {
      addIfNotEmpty(cmdList, coreSettings.getPluginUpdatePolicy().getCommandLineOption());

      if (!coreSettings.isUsePluginRegistry()) {
        cmdList.add("--no-plugin-registry");
      }
    }

    if (coreSettings.getOutputLevel() == MavenExecutionOptions.LoggingLevel.DEBUG) {
      cmdList.add("--debug");
    }
    if (coreSettings.isNonRecursive()) {
      cmdList.add("--non-recursive");
    }
    if (coreSettings.isPrintErrorStackTraces()) {
      cmdList.add("--errors");
    }

    if (coreSettings.isAlwaysUpdateSnapshots()) {
      cmdList.add("--update-snapshots");
    }

    addIfNotEmpty(cmdList, coreSettings.getFailureBehavior().getCommandLineOption());
    addIfNotEmpty(cmdList, coreSettings.getChecksumPolicy().getCommandLineOption());

    addOption(cmdList, "s", coreSettings.getUserSettingsFile());
    if (!StringUtil.isEmptyOrSpaces(coreSettings.getLocalRepository())) {
      cmdList.addProperty("maven.repo.local", coreSettings.getLocalRepository());
    }
  }

  private static void addIfNotEmpty(ParametersList parametersList, @Nullable String value) {
    if (!StringUtil.isEmptyOrSpaces(value)) {
      parametersList.add(value);
    }
  }

  private static String encodeProfiles(Map<String, Boolean> profiles) {
    StringBuilder stringBuilder = new StringBuilder();
    for (Map.Entry<String, Boolean> entry : profiles.entrySet()) {
      if (stringBuilder.length() != 0) {
        stringBuilder.append(",");
      }
      if (!entry.getValue()) {
        stringBuilder.append("!");
      }
      stringBuilder.append(entry.getKey());
    }
    return stringBuilder.toString();
  }

  private static class ProjectSettingsOpenerExecutionException extends ExecutionException implements HyperlinkListener {

    private final Project myProject;

    public ProjectSettingsOpenerExecutionException(final String s, Project project) {
      super(s);
      myProject = project;
    }

    @Override
    public void hyperlinkUpdate(HyperlinkEvent e) {
      if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED) return;

      ShowSettingsUtil.getInstance().showSettingsDialog(myProject, MavenSettings.DISPLAY_NAME);
    }
  }

  private static class ProjectJdkSettingsOpenerExecutionException extends ExecutionException implements HyperlinkListener {

    private final Project myProject;

    public ProjectJdkSettingsOpenerExecutionException(final String s, Project project) {
      super(s);
      myProject = project;
    }

    @Override
    public void hyperlinkUpdate(HyperlinkEvent e) {
      if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED) return;

      ProjectSettingsService.getInstance(myProject).openProjectSettings();
    }
  }

  private static class RunConfigurationOpenerExecutionException extends ExecutionException implements HyperlinkListener {

    private final MavenRunConfiguration myRunConfiguration;

    public RunConfigurationOpenerExecutionException(final String s, MavenRunConfiguration runConfiguration) {
      super(s);
      myRunConfiguration = runConfiguration;
    }

    @Override
    public void hyperlinkUpdate(HyperlinkEvent e) {
      if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED) return;

      Project project = myRunConfiguration.getProject();
      //RunManagerImpl runManager = (RunManagerImpl)RunManager.getInstance(project);
      //RunnerAndConfigurationSettings settings = runManager.getSettings(myRunConfiguration);
      //if (settings == null) {
      //  return;
      //}
      //
      //runManager.setSelectedConfiguration(settings);

      EditConfigurationsDialog dialog = new EditConfigurationsDialog(project);
      dialog.show();
    }
  }
}
