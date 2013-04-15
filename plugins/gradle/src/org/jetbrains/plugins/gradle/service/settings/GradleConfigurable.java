/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package org.jetbrains.plugins.gradle.service.settings;

import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.externalSystem.model.settings.LocationSettingType;
import com.intellij.openapi.externalSystem.service.settings.AbstractExternalProjectConfigurable;
import com.intellij.openapi.externalSystem.util.ExternalSystemUiUtil;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.TextComponentAccessor;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.service.GradleInstallationManager;
import org.jetbrains.plugins.gradle.settings.GradleSettings;
import org.jetbrains.plugins.gradle.settings.GradleSettingsListener;
import org.jetbrains.plugins.gradle.util.GradleBundle;
import org.jetbrains.plugins.gradle.util.GradleConstants;
import org.jetbrains.plugins.gradle.util.GradleUtil;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * @author Denis Zhdanov
 * @since 4/15/13 2:50 PM
 */
public class GradleConfigurable extends AbstractExternalProjectConfigurable<GradleSettingsListener, GradleSettings> {

  @NonNls public static final String HELP_TOPIC = "reference.settingsdialog.project.gradle";

  private static final long BALLOON_DELAY_MILLIS = TimeUnit.SECONDS.toMillis(1);

  @NotNull private final Alarm myAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD);

  @NotNull private LocationSettingType myGradleHomeSettingType = LocationSettingType.UNKNOWN;

  @NotNull private final JLabel myGradleHomeLabel       = new JBLabel(GradleBundle.message("gradle.settings.text.home.path"));
  @NotNull private final JLabel myServiceDirectoryLabel = new JBLabel(GradleBundle.message("gradle.settings.text.service.dir.path"));

  @NotNull private final Helper myHelper;

  @NotNull private TextFieldWithBrowseButton myGradleHomePathField;
  @NotNull private TextFieldWithBrowseButton myServiceDirectoryPathField;
  @NotNull private JBRadioButton             myUseWrapperButton;
  @NotNull private JBRadioButton             myUseLocalDistributionButton;
  @NotNull private JBCheckBox                myUseAutoImportBox;
  
  private boolean myShowBalloonIfNecessary;
  private boolean myGradleHomeModifiedByUser;
  private boolean myServiceDirectoryModifiedByUser;

  public GradleConfigurable(@Nullable Project project) {
    this(project, ServiceManager.getService(GradleInstallationManager.class));
  }

  public GradleConfigurable(@Nullable Project project, @NotNull GradleInstallationManager installationManager) {
    super(project, GradleConstants.SYSTEM_ID, false);
    myHelper = new DefaultHelper(installationManager);
  }

  public GradleConfigurable(@Nullable Project project, @NotNull GradleInstallationManager installationManager, @NotNull Helper helper) {
    super(project, GradleConstants.SYSTEM_ID, true);
    myHelper = helper;
  }

  @NotNull
  @Override
  public String getId() {
    return getHelpTopic();
  }

  @NotNull
  public String getHelpTopic() {
    return HELP_TOPIC;
  }

  @NotNull
  @Override
  protected JComponent buildContent(boolean testMode) {
    JPanel result = createContent();
    
    initLinkedProjectPathControl();
    initWrapperVsLocalControls();
    initGradleHome(testMode);
    initServiceDirectoryHome();
    myUseAutoImportBox = new JBCheckBox(GradleBundle.message("gradle.settings.text.use.auto.import"));

    // Provide radio buttons if gradle wrapper can be used for particular project.
    result.add(myUseWrapperButton, getFillLineConstraints());
    result.add(myUseLocalDistributionButton, getFillLineConstraints());

    result.add(myUseAutoImportBox, getFillLineConstraints());

    result.add(myGradleHomeLabel, getLabelConstraints());
    result.add(myGradleHomePathField, getFillLineConstraints());

    Insets insets = new Insets(5, 0, 0, 0);
    result.add(myServiceDirectoryLabel, getLabelConstraints().insets(insets));
    result.add(myServiceDirectoryPathField, getFillLineConstraints().insets(insets));
    
    return result;
  }

  @NotNull
  private JPanel createContent() {
    JPanel result = new JPanel(new GridBagLayout()) {
      @Override
      public void paint(Graphics g) {
        super.paint(g);
        showBalloonIfNecessary();
      }
    };
    result.addPropertyChangeListener(new PropertyChangeListener() {
      @Override
      public void propertyChange(PropertyChangeEvent evt) {
        if (!"ancestor".equals(evt.getPropertyName())) {
          return;
        }

        // Configure the balloon to show on initial configurable drawing.
        myShowBalloonIfNecessary = evt.getNewValue() != null && evt.getOldValue() == null;

        if (evt.getNewValue() == null && evt.getOldValue() != null) {
          // Cancel delayed balloons when the configurable is hidden.
          myAlarm.cancelAllRequests();
        }
      }
    });
    return result;
  }

  private void initLinkedProjectPathControl() {
    getLinkedExternalProjectPathField().getTextField().getDocument().addDocumentListener(new DocumentListener() {
      @Override
      public void insertUpdate(DocumentEvent e) {
        onLinkedProjectPathChange();
      }

      @Override
      public void removeUpdate(DocumentEvent e) {
        onLinkedProjectPathChange();
      }

      @Override
      public void changedUpdate(DocumentEvent e) {
        onLinkedProjectPathChange();
      }
    });

  }
  
  private void initWrapperVsLocalControls() {
    ActionListener listener = new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        boolean enabled = e.getSource() == myUseLocalDistributionButton;
        myGradleHomePathField.setEnabled(enabled);
        if (enabled) {
          showBalloonIfNecessary();
        }
        else {
          myAlarm.cancelAllRequests();
        }
      }
    };
    myUseWrapperButton = new JBRadioButton(GradleBundle.message("gradle.config.text.use.wrapper"), true);
    myUseWrapperButton.addActionListener(listener);
    myUseLocalDistributionButton = new JBRadioButton(GradleBundle.message("gradle.config.text.use.local.distribution"));
    myUseLocalDistributionButton.addActionListener(listener);
    ButtonGroup group = new ButtonGroup();
    group.add(myUseWrapperButton);
    group.add(myUseLocalDistributionButton);
  }

  private void initGradleHome(boolean testMode) {
    myGradleHomePathField = new TextFieldWithBrowseButton();

    FileChooserDescriptor fileChooserDescriptor = testMode ? new FileChooserDescriptor(true, false, false, false, false, false)
                                                           : GradleUtil.getGradleHomeFileChooserDescriptor();

    myGradleHomePathField.addBrowseFolderListener(
      "",
      GradleBundle.message("gradle.settings.text.home.path"),
      null,
      fileChooserDescriptor,
      TextComponentAccessor.TEXT_FIELD_WHOLE_TEXT,
      false
    );
    myGradleHomePathField.getTextField().getDocument().addDocumentListener(new DocumentListener() {
      @Override
      public void insertUpdate(DocumentEvent e) {
        useColorForPath(LocationSettingType.EXPLICIT_CORRECT, myGradleHomePathField);
        myGradleHomeModifiedByUser = true;
      }

      @Override
      public void removeUpdate(DocumentEvent e) {
        useColorForPath(LocationSettingType.EXPLICIT_CORRECT, myGradleHomePathField);
        myGradleHomeModifiedByUser = true;
      }

      @Override
      public void changedUpdate(DocumentEvent e) {
      }
    });
  }

  private void initServiceDirectoryHome() {
    myServiceDirectoryPathField = new TextFieldWithBrowseButton();
    myServiceDirectoryPathField.addBrowseFolderListener(
      "",
      GradleBundle.message("gradle.settings.title.service.dir.path"),
      getProject(),
      new FileChooserDescriptor(false, true, false, false, false, false),
      TextComponentAccessor.TEXT_FIELD_WHOLE_TEXT,
      false
    );
    myServiceDirectoryPathField.getTextField().getDocument().addDocumentListener(new DocumentListener() {
      @Override
      public void insertUpdate(DocumentEvent e) {
        useColorForPath(LocationSettingType.EXPLICIT_CORRECT, myServiceDirectoryPathField);
        myServiceDirectoryModifiedByUser = true;
      }

      @Override
      public void removeUpdate(DocumentEvent e) {
        useColorForPath(LocationSettingType.EXPLICIT_CORRECT, myServiceDirectoryPathField);
        myServiceDirectoryModifiedByUser = true;
      }

      @Override
      public void changedUpdate(DocumentEvent e) {
      }
    });
  }

  private void onLinkedProjectPathChange() {
    String linkedProjectPath = getLinkedExternalProjectPathField().getText();
    if (StringUtil.isEmpty(linkedProjectPath) || !myHelper.isGradleWrapperDefined(linkedProjectPath)) {
      myUseWrapperButton.setEnabled(false);
      myUseWrapperButton.setText(GradleBundle.message("gradle.config.text.use.wrapper.disabled"));
      myUseLocalDistributionButton.setSelected(true);
    }
    else {
      myUseWrapperButton.setText(GradleBundle.message("gradle.config.text.use.wrapper"));
      myUseWrapperButton.setEnabled(true);
      myUseWrapperButton.setSelected(true);
      myGradleHomePathField.setEnabled(false);
    }
  }

  @NotNull
  @Override
  protected FileChooserDescriptor getLinkedProjectConfigDescriptor() {
    return GradleUtil.getGradleProjectFileChooserDescriptor();
  }

  @NotNull
  public TextFieldWithBrowseButton getGradleHomePathField() {
    return myGradleHomePathField;
  }

  @Nullable
  public String getGradleHomePath() {
    return myGradleHomePathField.getText();
  }

  public boolean isPreferLocalInstallationToWrapper() {
    return myUseLocalDistributionButton.isSelected();
  }

  public boolean isUseAutoImport() {
    return myUseAutoImportBox.isSelected();
  }

  @NotNull
  JBRadioButton getUseWrapperButton() {
    return myUseWrapperButton;
  }

  void showBalloonIfNecessary() {
    if (!myShowBalloonIfNecessary || !myGradleHomePathField.isEnabled()) {
      return;
    }
    myShowBalloonIfNecessary = false;
    MessageType messageType = null;
    switch (myGradleHomeSettingType) {
      case DEDUCED:
        messageType = MessageType.INFO;
        break;
      case EXPLICIT_INCORRECT:
      case UNKNOWN:
        messageType = MessageType.ERROR;
        break;
      default:
    }
    if (messageType != null) {
      myHelper.showBalloon(messageType, myGradleHomeSettingType, BALLOON_DELAY_MILLIS);
    }
  }

  /**
   * Updates GUI of the gradle configurable in order to show deduced path to gradle (if possible).
   */
  private void deduceGradleHomeIfPossible() {
    File gradleHome = myHelper.getGradleHome(getProject());
    if (gradleHome == null) {
      myHelper.showBalloon(MessageType.WARNING, LocationSettingType.UNKNOWN, BALLOON_DELAY_MILLIS);
      return;
    }
    myGradleHomeSettingType = LocationSettingType.DEDUCED;
    myHelper.showBalloon(MessageType.INFO, LocationSettingType.DEDUCED, BALLOON_DELAY_MILLIS);
    myGradleHomePathField.setText(gradleHome.getPath());
    useColorForPath(LocationSettingType.DEDUCED, myGradleHomePathField);
    myGradleHomePathField.getTextField().setForeground(UIManager.getColor("TextField.inactiveForeground"));
    myGradleHomeModifiedByUser = false;
  }

  private void deduceServiceDirectoryIfPossible() {
    String path = System.getenv().get(GradleUtil.SYSTEM_DIRECTORY_PATH_KEY);
    if (StringUtil.isEmpty(path)) {
      path = new File(System.getProperty("user.home"), ".gradle").getAbsolutePath();
    }
    myServiceDirectoryPathField.setText(path);
    useColorForPath(LocationSettingType.DEDUCED, myServiceDirectoryPathField);
    myServiceDirectoryModifiedByUser = false;
  }
  
  private class DelayedBalloonInfo implements Runnable {
    private final MessageType myMessageType;
    private final String      myText;
    private final long        myTriggerTime;

    DelayedBalloonInfo(@NotNull MessageType messageType, @NotNull LocationSettingType settingType, long delayMillis) {
      myMessageType = messageType;
      myText = settingType.getDescription(GradleConstants.SYSTEM_ID);
      myTriggerTime = System.currentTimeMillis() + delayMillis;
    }

    @Override
    public void run() {
      long diff = myTriggerTime - System.currentTimeMillis();
      if (diff > 0) {
        myAlarm.cancelAllRequests();
        myAlarm.addRequest(this, diff);
        return;
      }
      if (!myGradleHomePathField.isShowing()) {
        // Don't schedule the balloon if the configurable is hidden.
        return;
      }
      ExternalSystemUiUtil.showBalloon(myGradleHomePathField, myMessageType, myText);
    }
  }

  /**
   * Encapsulates functionality which default implementation is backed by static API usage (IJ infrastructure limitation).
   * <p/>
   * The main idea is to allow to provide a mock implementation for {@link AbstractExternalProjectConfigurable} logic testing.
   */
  interface Helper {
    boolean isGradleSdkHome(@NotNull File file);

    @Nullable
    File getGradleHome(@Nullable Project project);

    @NotNull
    Project getDefaultProject();

    @NotNull
    GradleSettings getSettings(@NotNull Project project);

    void applySettings(@Nullable String linkedProjectPath,
                       @Nullable String gradleHomePath,
                       boolean preferLocalInstallationToWrapper,
                       boolean useAutoImport,
                       @Nullable String serviceDirectoryPath,
                       @NotNull Project project);

    void applyPreferLocalInstallationToWrapper(boolean preferLocalToWrapper, @NotNull Project project);

    boolean isGradleWrapperDefined(@Nullable String linkedProjectPath);

    void showBalloon(@NotNull MessageType messageType, @NotNull LocationSettingType settingType, long delayMillis);
  }

  private class DefaultHelper implements Helper {

    @NotNull private final GradleInstallationManager myInstallationManager;

    DefaultHelper(@NotNull GradleInstallationManager installationManager) {
      myInstallationManager = installationManager;
    }

    @Override
    public boolean isGradleSdkHome(@NotNull File file) {
      return myInstallationManager.isGradleSdkHome(file);
    }

    @Nullable
    @Override
    public File getGradleHome(@Nullable Project project) {
      return myInstallationManager.getGradleHome(project);
    }

    @NotNull
    @Override
    public Project getDefaultProject() {
      return ProjectManager.getInstance().getDefaultProject();
    }

    @NotNull
    @Override
    public GradleSettings getSettings(@NotNull Project project) {
      return GradleSettings.getInstance(project);
    }

    @Override
    public void applySettings(@Nullable String linkedProjectPath,
                              @Nullable String gradleHomePath,
                              boolean preferLocalInstallationToWrapper,
                              boolean useAutoImport,
                              @Nullable String serviceDirectoryPath,
                              @NotNull Project project)
    {
      // TODO den implement
//      GradleSettings.applySettings(
//        linkedProjectPath, gradleHomePath, preferLocalInstallationToWrapper, useAutoImport, serviceDirectoryPath, project
//      ); 
    }

    @Override
    public void applyPreferLocalInstallationToWrapper(boolean preferLocalToWrapper, @NotNull Project project) {
      // TODO den implement
//      GradleSettings.applyPreferLocalInstallationToWrapper(preferLocalToWrapper, project);
    }

    @Override
    public boolean isGradleWrapperDefined(@Nullable String linkedProjectPath) {
      return GradleUtil.isGradleWrapperDefined(linkedProjectPath);
    }

    @Override
    public void showBalloon(@NotNull MessageType messageType, @NotNull LocationSettingType settingType, long delayMillis) {
      new DelayedBalloonInfo(messageType, settingType, delayMillis).run();
    }
  }
}
