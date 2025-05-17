package com.joshua;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser; // Added
import javafx.stage.Stage;

import java.io.File; // Added

public class DownloadManagerUI extends Application {

    // --- UI Constants ---
    private static final String APP_TITLE = "Download Manager";
    private static final String BTN_TEXT_START_DOWNLOAD = "Start Download";
    private static final String BTN_TEXT_PAUSE = "Pause";
    private static final String BTN_TEXT_RESUME = "Resume";
    private static final String LABEL_STATUS_IDLE = "Idle";
    private static final String LABEL_STATUS_DOWNLOADING = "Downloading...";
    private static final String LABEL_STATUS_PAUSED = "Paused";
    private static final String LABEL_STATUS_COMPLETE = "Download complete.";
    private static final String LABEL_STATUS_ERROR_PREFIX = "Error: ";
    private static final String LABEL_SPEED_ETA_DEFAULT = "Speed: 0 KB/s | ETA: --:--";


    // --- Default Settings ---
    private int maxThreads = 4;
    private int maxSpeedLimit = 0; // 0 means no limit
    private String defaultSaveDirectory; // Added
    private TextField saveDirectoryField; // Added: Field for settings UI


    // --- UI Components ---
    private TextField urlField;
    private TextField fileNameField;
    private Button startButton;
    private Button pauseResumeButton;
    private ProgressBar progressBar;
    private Label statusLabel;
    private Label speedEtaLabel;

    private VBox downloadPane;
    private VBox settingsPane;
    private BorderPane rootPane;

    // --- State Variables ---
    private boolean isPaused = false;
    private DownloaderManager downloaderManager;

    public DownloadManagerUI() {
        // Initialize defaultSaveDirectory here or in start() before UI creation
        // Using user.home/MyDownloads as a default
        String homeDir = System.getProperty("user.home");
        this.defaultSaveDirectory = homeDir + File.separator + "MyDownloads";
    }

    @Override
    public void start(Stage primaryStage) {
        ensureDefaultSaveDirectoryExists(); // Ensure it exists on startup

        rootPane = new BorderPane();

        MenuBar menuBar = createMenuBar(primaryStage);
        rootPane.setTop(menuBar);

        downloadPane = createDownloadPane();
        rootPane.setCenter(downloadPane);

        settingsPane = createSettingsPane(); // Create settings pane, now including directory setting

        Scene scene = new Scene(rootPane, 600, 500); // Slightly increased height for new setting
        primaryStage.setTitle(APP_TITLE);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private void ensureDefaultSaveDirectoryExists() {
        File dir = new File(defaultSaveDirectory);
        if (!dir.exists()) {
            if (dir.mkdirs()) {
                System.out.println("Created default save directory: " + defaultSaveDirectory);
            } else {
                System.err.println("Could not create default save directory: " + defaultSaveDirectory);
                // Optionally show an alert to the user
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.WARNING);
                    alert.setTitle("Directory Creation Failed");
                    alert.setHeaderText("Could not create default save directory");
                    alert.setContentText("The default save directory (" + defaultSaveDirectory + ") could not be created. Please check permissions or set a valid directory in Settings. Downloads to the default directory might fail.");
                    alert.showAndWait();
                });
            }
        }
    }

    private MenuBar createMenuBar(Stage primaryStage) {
        MenuBar menuBar = new MenuBar();

        Menu fileMenu = new Menu("File");
        MenuItem exitItem = new MenuItem("Exit");
        exitItem.setOnAction(e -> primaryStage.close());
        fileMenu.getItems().add(exitItem);

        Menu settingsMenu = new Menu("Settings");
        MenuItem appSettingsItem = new MenuItem("Application Settings");
        appSettingsItem.setOnAction(e -> {
            // Refresh settings fields when showing the pane
            if (saveDirectoryField != null) {
                saveDirectoryField.setText(this.defaultSaveDirectory);
            }
            // Similar for threadInput and speedLimitInput if needed, though they are usually fine
            rootPane.setCenter(settingsPane);
        });
        settingsMenu.getItems().add(appSettingsItem);

        Menu helpMenu = new Menu("Help");
        MenuItem aboutItem = new MenuItem("About");
        aboutItem.setOnAction(e -> showAboutDialog());
        helpMenu.getItems().add(aboutItem);

        menuBar.getMenus().addAll(fileMenu, settingsMenu, helpMenu);
        return menuBar;
    }

    private VBox createDownloadPane() {
        urlField = new TextField();
        urlField.setPromptText("Enter file URL");

        fileNameField = new TextField();
        fileNameField.setPromptText("Enter output file name (optional, uses default directory)");

        startButton = new Button(BTN_TEXT_START_DOWNLOAD);
        pauseResumeButton = new Button(BTN_TEXT_PAUSE);
        pauseResumeButton.setDisable(true);

        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(400);
        progressBar.setMaxWidth(Double.MAX_VALUE);

        statusLabel = new Label(LABEL_STATUS_IDLE);
        speedEtaLabel = new Label(LABEL_SPEED_ETA_DEFAULT);

        startButton.setOnAction(e -> handleStartDownload());
        pauseResumeButton.setOnAction(e -> handlePauseResume());

        VBox pane = new VBox(10, urlField, fileNameField, startButton, pauseResumeButton, progressBar, statusLabel, speedEtaLabel);
        pane.setPadding(new Insets(15));
        return pane;
    }

    private VBox createSettingsPane() {
        Label threadLabel = new Label("Set Max Threads (1 to 100) - Applies to new downloads:");
        TextField threadInput = new TextField(String.valueOf(maxThreads));
        threadInput.setPromptText("Number of threads");

        Label speedLimitLabel = new Label("Set Max Speed Limit (KB/s, 0 = no limit):");
        TextField speedLimitInput = new TextField(String.valueOf(maxSpeedLimit));
        speedLimitInput.setPromptText("Max speed limit in KB/s");

        // --- Save Directory Setting ---
        Label saveDirLabel = new Label("Default Save Directory:");
        saveDirectoryField = new TextField(this.defaultSaveDirectory); // Initialize with current default
        Button browseSaveDirButton = new Button("Browse...");

        browseSaveDirButton.setOnAction(e -> {
            DirectoryChooser directoryChooser = new DirectoryChooser();
            directoryChooser.setTitle("Select Default Save Directory");
            File initialDir = new File(saveDirectoryField.getText()); // Use current text field value as initial
            if (initialDir.exists() && initialDir.isDirectory()) {
                directoryChooser.setInitialDirectory(initialDir);
            } else {
                 File homeDir = new File(System.getProperty("user.home"));
                 if (homeDir.exists() && homeDir.isDirectory()) {
                     directoryChooser.setInitialDirectory(homeDir);
                 }
            }
            File selectedDirectory = directoryChooser.showDialog(rootPane.getScene().getWindow());
            if (selectedDirectory != null) {
                saveDirectoryField.setText(selectedDirectory.getAbsolutePath());
            }
        });
        // --- End Save Directory Setting ---


        Button saveSettingsBtn = new Button("Save Settings");
        Button backBtn = new Button("Back to Downloads");
        Label settingsStatus = new Label();

        saveSettingsBtn.setOnAction(e -> {
            settingsStatus.setText("");
            String feedback = "";
            boolean settingsUpdated = false;

            try {
                // Threads
                int threads = Integer.parseInt(threadInput.getText());
                if (threads < 1 || threads > 100) {
                    settingsStatus.setText("Threads must be between 1 and 100.");
                    return;
                }
                this.maxThreads = threads;
                feedback += "Max threads: " + this.maxThreads;
                settingsUpdated = true;

                // Speed Limit
                int speedLimitKBps = Integer.parseInt(speedLimitInput.getText());
                if (speedLimitKBps < 0) {
                    settingsStatus.setText("Speed limit must be 0 or greater.");
                    return;
                }
                this.maxSpeedLimit = speedLimitKBps;
                feedback += (settingsUpdated ? ", " : "") + "Max speed: " + (this.maxSpeedLimit == 0 ? "Unlimited" : this.maxSpeedLimit + " KB/s");
                settingsUpdated = true;

                // Save Directory
                String newSaveDirText = saveDirectoryField.getText().trim();
                if (newSaveDirText.isEmpty()) {
                    settingsStatus.setText("Save directory cannot be empty.");
                    return;
                }
                File newSaveDirFile = new File(newSaveDirText);
                // Attempt to create if it doesn't exist
                if (!newSaveDirFile.exists()) {
                    if (!newSaveDirFile.mkdirs()) {
                        settingsStatus.setText("Warning: Could not create specified save directory: " + newSaveDirText + ". It will be used, but downloads might fail.");
                        // We still save the path, creation will be re-attempted at download time.
                    }
                } else if (!newSaveDirFile.isDirectory()) {
                    settingsStatus.setText("Specified save path is not a directory: " + newSaveDirText);
                    return;
                }
                this.defaultSaveDirectory = newSaveDirFile.getAbsolutePath(); // Store absolute path
                saveDirectoryField.setText(this.defaultSaveDirectory); // Update field to show absolute path
                feedback += (settingsUpdated ? ", " : "") + "Save directory: " + this.defaultSaveDirectory;
                settingsUpdated = true;


                // Update active download if applicable
                if (downloaderManager != null && (startButton.isDisabled() && !pauseResumeButton.getText().equals(BTN_TEXT_RESUME))) {
                    long newSpeedLimitBps = (long) this.maxSpeedLimit * 1024;
                    downloaderManager.updateMaxSpeedLimit(newSpeedLimitBps);
                    feedback += "\nActive download speed limit updated.";
                }
                
                settingsStatus.setText(feedback.isEmpty() ? "No changes made." : "Settings saved: " + feedback);

            } catch (NumberFormatException ex) {
                settingsStatus.setText("Please enter valid numeric values for threads and speed limit.");
            }
        });

        backBtn.setOnAction(e -> {
            settingsStatus.setText("");
            rootPane.setCenter(downloadPane);
        });

        VBox pane = new VBox(10,
                threadLabel, threadInput,
                speedLimitLabel, speedLimitInput,
                saveDirLabel, saveDirectoryField, browseSaveDirButton, // Added directory elements
                saveSettingsBtn, backBtn, settingsStatus);
        pane.setPadding(new Insets(15));
        return pane;
    }

    private void handleStartDownload() {
        String url = urlField.getText();
        if (url == null || url.isBlank()) {
            statusLabel.setText("Please enter a valid URL.");
            return;
        }

        String fileNameInput = fileNameField.getText().trim();
        String fullOutputFilePath;

        // Determine the final output path
        if (fileNameInput.isEmpty()) {
            // Filename is empty, derive from URL and use default directory
            String derivedFileName;
            try {
                String path = new java.net.URL(url).getPath();
                derivedFileName = path.substring(path.lastIndexOf('/') + 1);
                if (derivedFileName.isEmpty() || derivedFileName.endsWith("/")) {
                    derivedFileName = "downloaded_file";
                }
            } catch (java.net.MalformedURLException e) {
                derivedFileName = "downloaded_file";
            }
            fileNameField.setText(derivedFileName); // Update UI with derived name
            File targetDirFile = new File(this.defaultSaveDirectory);
            fullOutputFilePath = new File(targetDirFile, derivedFileName).getAbsolutePath();
        } else {
            File providedFile = new File(fileNameInput);
            if (providedFile.isAbsolute()) {
                // User provided an absolute path
                fullOutputFilePath = providedFile.getAbsolutePath();
            } else {
                // User provided a relative path or just a filename, use default directory
                File targetDirFile = new File(this.defaultSaveDirectory);
                fullOutputFilePath = new File(targetDirFile, fileNameInput).getAbsolutePath();
            }
        }

        // Ensure the parent directory of the final output file exists
        File finalOutputFile = new File(fullOutputFilePath);
        File parentDir = finalOutputFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                statusLabel.setText(LABEL_STATUS_ERROR_PREFIX + "Could not create directory: " + parentDir.getAbsolutePath());
                resetUIForNewDownload(true);
                return;
            }
        }


        // Update UI for download start
        statusLabel.setText(LABEL_STATUS_DOWNLOADING);
        progressBar.setProgress(0);
        speedEtaLabel.setText(LABEL_SPEED_ETA_DEFAULT);
        startButton.setDisable(true);
        pauseResumeButton.setDisable(false);
        pauseResumeButton.setText(BTN_TEXT_PAUSE);
        urlField.setDisable(true);
        fileNameField.setDisable(true);
        isPaused = false;

        long currentMaxSpeedBps = (long) this.maxSpeedLimit * 1024;

        downloaderManager = new DownloaderManager(url, fullOutputFilePath, this.maxThreads, (downloaded, total, speedBps) -> {
            double progress = (total > 0) ? (double) downloaded / total : 0;
            long remainingBytes = total - downloaded;
            String speedText = String.format("%.2f KB/s", speedBps / 1024.0);
            String etaText = (speedBps > 0 && remainingBytes > 0) ?
                             String.format("%d:%02d", remainingBytes / speedBps / 60, (remainingBytes / speedBps) % 60) : "--:--";

            Platform.runLater(() -> {
                progressBar.setProgress(progress);
                speedEtaLabel.setText(String.format("Speed: %s | ETA: %s", speedText, etaText));
                if (downloaded == total && total >= 0) { // Allow 0 byte file completion
                    statusLabel.setText(LABEL_STATUS_COMPLETE + " (" + fullOutputFilePath + ")");
                    resetUIForNewDownload(false);
                    downloaderManager = null;
                } else if (total > 0) {
                    statusLabel.setText(String.format("Downloaded %s / %s to %s", formatBytes(downloaded), formatBytes(total), new File(fullOutputFilePath).getName()));
                } else if (total == 0 && downloaded == 0) { // For 0-byte files just starting
                     statusLabel.setText(String.format("Downloading 0 B file to %s", new File(fullOutputFilePath).getName()));
                }
            });
        }, currentMaxSpeedBps);

        new Thread(() -> {
            try {
                downloaderManager.download();
                Platform.runLater(() -> {
                    // Final completion check, especially for 0-byte files or if progress listener didn't make it to 100%
                    if (!statusLabel.getText().startsWith(LABEL_STATUS_COMPLETE)) {
                        if (progressBar.getProgress() >= 0.999 || (progressBar.getProgress() == 0 && new File(fullOutputFilePath).exists() && new File(fullOutputFilePath).length() == 0) ) {
                            statusLabel.setText(LABEL_STATUS_COMPLETE + " (" + fullOutputFilePath + ")");
                            resetUIForNewDownload(false);
                            downloaderManager = null;
                        }
                    }
                });
            } catch (Exception ex) {
                // ex.printStackTrace(); // For debugging
                Platform.runLater(() -> {
                    statusLabel.setText(LABEL_STATUS_ERROR_PREFIX + ex.getMessage());
                    resetUIForNewDownload(true);
                    downloaderManager = null;
                });
            }
        }).start();
    }

    private void handlePauseResume() {
        if (downloaderManager == null) return;
        if (!isPaused) {
            downloaderManager.pause();
            pauseResumeButton.setText(BTN_TEXT_RESUME);
            statusLabel.setText(LABEL_STATUS_PAUSED);
            isPaused = true;
        } else {
            downloaderManager.resume();
            pauseResumeButton.setText(BTN_TEXT_PAUSE);
            statusLabel.setText(LABEL_STATUS_DOWNLOADING);
            isPaused = false;
        }
    }

    private void resetUIForNewDownload(boolean enableFields) {
        startButton.setDisable(false);
        pauseResumeButton.setDisable(true);
        pauseResumeButton.setText(BTN_TEXT_PAUSE);
        urlField.setDisable(!enableFields);
        fileNameField.setDisable(!enableFields);
        if (enableFields) {
            progressBar.setProgress(0);
            speedEtaLabel.setText(LABEL_SPEED_ETA_DEFAULT);
            // Don't reset statusLabel to IDLE if it's an error message or completion message.
            // It will be set to DOWNLOADING or similar on next successful start.
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    private void showAboutDialog() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("About " + APP_TITLE);
        alert.setHeaderText(APP_TITLE);
        alert.setContentText("Multi-threaded Download Manager\nVersion 1.3 (Save Directory)\nCreated by Joshua (and refined by AI)");
        alert.showAndWait();
    }

    public static void main(String[] args) {
        launch(args);
    }
}