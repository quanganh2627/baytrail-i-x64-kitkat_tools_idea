/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.ide.util.projectWizard;

import com.intellij.ide.IdeBundle;
import com.intellij.ide.highlighter.ModuleFileType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.module.*;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.DumbAwareRunnable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ui.configuration.ModulesProvider;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.EventDispatcher;
import org.jdom.JDOMException;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.util.*;

public abstract class ModuleBuilder extends AbstractModuleBuilder {
  private static final ExtensionPointName<ModuleBuilderFactory> EP_NAME = ExtensionPointName.create("com.intellij.moduleBuilder");

  private static final Logger LOG = Logger.getInstance("#com.intellij.ide.util.projectWizard.ModuleBuilder");
  protected Sdk myJdk;
  private String myName;
  @NonNls private String myModuleFilePath;
  private String myContentEntryPath;
  private final Set<ModuleConfigurationUpdater> myUpdaters = new HashSet<ModuleConfigurationUpdater>();
  private final EventDispatcher<ModuleBuilderListener> myDispatcher = EventDispatcher.create(ModuleBuilderListener.class);
  private Map<String, Boolean> myAvailableFrameworks;

  @NotNull
  public static List<ModuleBuilder> getAllBuilders() {
    final ArrayList<ModuleBuilder> result = new ArrayList<ModuleBuilder>();
    for (final ModuleType moduleType : ModuleTypeManager.getInstance().getRegisteredTypes()) {
      result.add(moduleType.createModuleBuilder());
    }
    for (ModuleBuilderFactory factory : EP_NAME.getExtensions()) {
      result.add(factory.createBuilder());
    }
    return result;
  }

  @Nullable
  protected final String acceptParameter(String param) {
    return param != null && param.length() > 0 ? param : null;
  }

  public String getName() {
    return myName;
  }

  @Override
  @Nullable
  public String getBuilderId() {
    ModuleType moduleType = getModuleType();
    return moduleType == null ? null : moduleType.getId();
  }

  @Override
  public ModuleWizardStep[] createWizardSteps(@NotNull WizardContext wizardContext, @NotNull ModulesProvider modulesProvider) {
    ModuleType moduleType = getModuleType();
    return moduleType == null ? ModuleWizardStep.EMPTY_ARRAY : moduleType.createWizardSteps(wizardContext, this, modulesProvider);
  }

  /**
   * Typically delegates to ModuleType (e.g. JavaModuleType) that is more generic than ModuleBuilder
   *
   * @param settingsStep step to be modified
   * @return callback ({@link com.intellij.ide.util.projectWizard.ModuleWizardStep#validate()}
   *         and {@link com.intellij.ide.util.projectWizard.ModuleWizardStep#updateDataModel()}
   *         will be invoked)
   */
  @Override
  @Nullable
  public ModuleWizardStep modifySettingsStep(@NotNull SettingsStep settingsStep) {
    ModuleType type = getModuleType();
    if (type == null) {
      return null;
    }
    else {
      final ModuleWizardStep step = type.modifySettingsStep(settingsStep, this);
      final List<WizardInputField> fields = getAdditionalFields();
      for (WizardInputField field : fields) {
        field.addToSettings(settingsStep);
      }
      return new ModuleWizardStep() {
        @Override
        public JComponent getComponent() {
          return null;
        }

        @Override
        public void updateDataModel() {
          if (step != null) {
            step.updateDataModel();
          }
        }

        @Override
        public boolean validate() throws ConfigurationException {
          for (WizardInputField field : fields) {
            if (!field.validate()) {
              return false;
            }
          }
          return step == null || step.validate();
        }
      };
    }
  }

  protected List<WizardInputField> getAdditionalFields() {
    return Collections.emptyList();
  }

  @Override
  public void setName(String name) {
    myName = acceptParameter(name);
  }

  public String getModuleFilePath() {
    return myModuleFilePath;
  }

  public void addModuleConfigurationUpdater(ModuleConfigurationUpdater updater) {
    myUpdaters.add(updater);
  }

  @Override
  public void setModuleFilePath(@NonNls String path) {
    myModuleFilePath = acceptParameter(path);
  }

  @Nullable
  public String getContentEntryPath() {
    if (myContentEntryPath == null) {
      final String directory = getModuleFileDirectory();
      if (directory == null) {
        return null;
      }
      new File(directory).mkdirs();
      return directory;
    }
    return myContentEntryPath;
  }

  @Override
  public void setContentEntryPath(String moduleRootPath) {
    final String path = acceptParameter(moduleRootPath);
    if (path != null) {
      try {
        myContentEntryPath = FileUtil.resolveShortWindowsName(path);
      }
      catch (IOException e) {
        myContentEntryPath = path;
      }
    }
    else {
      myContentEntryPath = null;
    }
    if (myContentEntryPath != null) {
      myContentEntryPath = myContentEntryPath.replace(File.separatorChar, '/');
    }
  }

  protected @Nullable ContentEntry doAddContentEntry(ModifiableRootModel modifiableRootModel) {
    final String contentEntryPath = getContentEntryPath();
    if (contentEntryPath == null) return null;
    new File(contentEntryPath).mkdirs();
    final VirtualFile moduleContentRoot = LocalFileSystem.getInstance().refreshAndFindFileByPath(contentEntryPath.replace('\\', '/'));
    if (moduleContentRoot == null) return null;
    return modifiableRootModel.addContentEntry(moduleContentRoot);
  }

  @Nullable
  public String getModuleFileDirectory() {
    if (myModuleFilePath == null) {
      return null;
    }
    final String parent = new File(myModuleFilePath).getParent();
    if (parent == null) {
      return null;
    }
    return parent.replace(File.separatorChar, '/');
  }

  @NotNull
  public Module createModule(@NotNull ModifiableModuleModel moduleModel)
    throws InvalidDataException, IOException, ModuleWithNameAlreadyExists, JDOMException, ConfigurationException {
    LOG.assertTrue(myName != null);
    LOG.assertTrue(myModuleFilePath != null);

    deleteModuleFile(myModuleFilePath);
    final ModuleType moduleType = getModuleType();
    final Module module = moduleModel.newModule(myModuleFilePath, moduleType.getId());
    setupModule(module);

    return module;
  }

  protected void setupModule(Module module) throws ConfigurationException {
    final ModifiableRootModel modifiableModel = ModuleRootManager.getInstance(module).getModifiableModel();
    setupRootModel(modifiableModel);
    for (ModuleConfigurationUpdater updater : myUpdaters) {
      updater.update(module, modifiableModel);
    }
    modifiableModel.commit();
  }

  private void onModuleInitialized(final Module module) {
    myDispatcher.getMulticaster().moduleCreated(module);
  }

  public abstract void setupRootModel(ModifiableRootModel modifiableRootModel) throws ConfigurationException;

  public abstract ModuleType getModuleType();

  @NotNull
  public Module createAndCommitIfNeeded(@NotNull Project project, @Nullable ModifiableModuleModel model, boolean runFromProjectWizard)
    throws InvalidDataException, ConfigurationException, IOException, JDOMException, ModuleWithNameAlreadyExists {
    final ModifiableModuleModel moduleModel = model != null ? model : ModuleManager.getInstance(project).getModifiableModel();
    final Module module = createModule(moduleModel);
    if (model == null) moduleModel.commit();

    if (runFromProjectWizard) {
      StartupManager.getInstance(module.getProject()).runWhenProjectIsInitialized(new DumbAwareRunnable() {
        @Override
        public void run() {
          ApplicationManager.getApplication().runWriteAction(new Runnable() {
            @Override
            public void run() {
              onModuleInitialized(module);
            }
          });
        }
      });
    }
    else {
      onModuleInitialized(module);
    }
    return module;
  }


  public void addListener(ModuleBuilderListener listener) {
    myDispatcher.addListener(listener);
  }

  public void removeListener(ModuleBuilderListener listener) {
    myDispatcher.removeListener(listener);
  }

  public boolean canCreateModule() {
    return true;
  }

  @Override
  @Nullable
  public List<Module> commit(final Project project, final ModifiableModuleModel model, final ModulesProvider modulesProvider) {
    final Module module = commitModule(project, model);
    return module != null ? Collections.singletonList(module) : null;
  }

  @Nullable
  public Module commitModule(@NotNull final Project project, @Nullable final ModifiableModuleModel model) {
    if (canCreateModule()) {
      if (myName == null) {
        myName = project.getName();
      }
      if (myModuleFilePath == null) {
        myModuleFilePath = project.getBaseDir().getPath() + File.separator + myName + ModuleFileType.DOT_DEFAULT_EXTENSION;
      }
      try {
        return ApplicationManager.getApplication().runWriteAction(new ThrowableComputable<Module, Exception>() {
          @Override
          public Module compute() throws Exception {
            return createAndCommitIfNeeded(project, model, true);
          }
        });
      }
      catch (Exception ex) {
        LOG.warn(ex);
        Messages.showErrorDialog(IdeBundle.message("error.adding.module.to.project", ex.getMessage()), IdeBundle.message("title.add.module"));
      }
    }
    return null;
  }

  public static void deleteModuleFile(String moduleFilePath) {
    final File moduleFile = new File(moduleFilePath);
    if (moduleFile.exists()) {
      FileUtil.delete(moduleFile);
    }
    final VirtualFile file = LocalFileSystem.getInstance().findFileByIoFile(moduleFile);
    if (file != null) {
      file.refresh(false, false);
    }
  }

  public Icon getBigIcon() {
    return getModuleType().getBigIcon();
  }

  public Icon getNodeIcon() {
    return getModuleType().getNodeIcon(false);
  }

  public String getDescription() {
    return getModuleType().getDescription();
  }

  public String getPresentableName() {
    return getModuleType().getName();
  }

  public String getGroupName() {
    return getPresentableName().split(" ")[0];
  }

  public void updateFrom(ModuleBuilder from) {
    myName = from.getName();
    myContentEntryPath = from.getContentEntryPath();
    myModuleFilePath = from.getModuleFilePath();
  }

  public void setModuleJdk(Sdk jdk) {
    myJdk = jdk;
  }

  public Sdk getModuleJdk() {
    return myJdk;
  }

  public Map<String, Boolean> getAvailableFrameworks() {
    return myAvailableFrameworks;
  }

  public void setAvailableFrameworks(Map<String, Boolean> availableFrameworks) {
    myAvailableFrameworks = availableFrameworks;
  }

  public static abstract class ModuleConfigurationUpdater {

    public abstract void update(@NotNull Module module, @NotNull ModifiableRootModel rootModel);

  }
}
