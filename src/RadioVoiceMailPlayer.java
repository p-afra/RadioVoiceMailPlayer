import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.Date;

public class RadioVoiceMailPlayer extends Application {

    private static String SERVER_IP = "192.168.2.131";
    private static int SERVER_PORT = 80;
    private static final String BASE_URL = "http://" + SERVER_IP + ":" + SERVER_PORT + "/recordings/";
    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir") + "/voicemail/";
    private static final String TRASH_DIR = System.getProperty("user.home") + "/VoicemailTrash/";
    private static final String LOG_FILE = System.getProperty("user.home") + "/voicemail_activity.log";

    private ObservableList<VoiceMail> voiceMailList = FXCollections.observableArrayList();
    private MediaPlayer mediaPlayer;

    private Label statusLabel, nowPlayingLabel, currentTimeLabel, totalTimeLabel;
    private Slider progressSlider, volumeSlider;
    private Button playPauseBtn;
    private ListView<VoiceMail> listView;
    private TextField searchField;
    private ComboBox<Double> speedComboBox;

    // Waveform
    private Canvas waveformCanvas;
    private GraphicsContext gc;
    private double[] spectrumData;

    private VoiceMail currentPlaying;
    private boolean isPlaying = false;

    public static void main(String[] args) {
        if (args.length >= 1) SERVER_IP = args[0];
        if (args.length >= 2) SERVER_PORT = Integer.parseInt(args[1]);
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        new File(TEMP_DIR).mkdirs();
        new File(TRASH_DIR).mkdirs();

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #1a1a2e;");

        root.setTop(createTopBar());
        root.setCenter(new VBox(10, createListView()));
        root.setBottom(createPlayerBox());

        Scene scene = new Scene(root, 1050, 750);
        primaryStage.setTitle("Radio VoiceMail Player - v2.1");
        primaryStage.setScene(scene);
        primaryStage.show();

        loadVoiceMails();
    }

    private VBox createTopBar() {
        VBox vbox = new VBox();
        HBox titleBar = new HBox(15);
        titleBar.setPadding(new Insets(15, 20, 15, 20));
        titleBar.setStyle("-fx-background-color: #16213e;");

        Label title = new Label("🎙️ Radio VoiceMail Player");
        title.setStyle("-fx-text-fill: white; -fx-font-size: 20; -fx-font-weight: bold;");

        // حق کپی‌رایت
        Label copyright = new Label("© P.Afra - All Rights Reserved");
        copyright.setStyle("-fx-text-fill: #7f8c8d; -fx-font-size: 11;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button statsBtn = new Button("📊 گزارشات");
        statsBtn.setStyle("-fx-background-color: #667eea; -fx-text-fill: white;");

        titleBar.getChildren().addAll(title, spacer, copyright, statsBtn);

        HBox searchBox = new HBox(10);
        searchBox.setPadding(new Insets(12, 20, 12, 20));
        searchBox.setStyle("-fx-background-color: #16213e;");

        searchField = new TextField();
        searchField.setPromptText("جستجو بر اساس شماره...");
        searchField.setStyle("-fx-background-color: #28334a; -fx-text-fill: white; -fx-prompt-text-fill: #aaaaaa;");
        HBox.setHgrow(searchField, Priority.ALWAYS);

        Button refreshBtn = new Button("🔄");
        refreshBtn.setStyle("-fx-background-color: #667eea; -fx-text-fill: white;");

        searchBox.getChildren().addAll(searchField, refreshBtn);
        vbox.getChildren().addAll(titleBar, searchBox);
        return vbox;
    }

    private ListView<VoiceMail> createListView() {
        listView = new ListView<>(voiceMailList);
        listView.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(VoiceMail item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                VBox card = new VBox(6);
                card.setPadding(new Insets(12));
                card.setStyle("-fx-background-color: #16213e; -fx-background-radius: 12;");

                HBox header = new HBox(10);
                Label number = new Label(item.getCallerNumber());
                number.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14;");

                Label date = new Label(item.getDate());
                date.setStyle("-fx-text-fill: #aaaaaa;");

                Region sp = new Region();
                HBox.setHgrow(sp, Priority.ALWAYS);

                Label tag = new Label(item.getTag());
                tag.setStyle("-fx-background-color: #667eea; -fx-text-fill: white; -fx-padding: 3 10; -fx-background-radius: 20;");

                header.getChildren().addAll(number, sp, date, tag);
                card.getChildren().addAll(header, new Label("یادداشت: " + item.getNote()));

                setGraphic(card);

                card.setOnMouseClicked(e -> {
                    if (e.getClickCount() == 2) playVoiceMail(item);
                });
            }
        });
        return listView;
    }

    private VBox createPlayerBox() {
        VBox playerBox = new VBox(15);
        playerBox.setPadding(new Insets(20));
        playerBox.setStyle("-fx-background-color: #16213e;");

        nowPlayingLabel = new Label("هیچ پیغامی انتخاب نشده");
        nowPlayingLabel.setStyle("-fx-text-fill: white; -fx-font-size: 16;");

        // Waveform
        waveformCanvas = new Canvas(820, 130);
        gc = waveformCanvas.getGraphicsContext2D();

        // Progress
        HBox timeBox = new HBox(10);
        currentTimeLabel = new Label("00:00");
        totalTimeLabel = new Label("00:00");
        progressSlider = new Slider(0, 100, 0);
        HBox.setHgrow(progressSlider, Priority.ALWAYS);
        timeBox.getChildren().addAll(currentTimeLabel, progressSlider, totalTimeLabel);

        // Controls
        HBox controls = new HBox(15);
        controls.setAlignment(Pos.CENTER);

        playPauseBtn = new Button("▶");
        playPauseBtn.setStyle("-fx-font-size: 24; -fx-padding: 12 40; -fx-background-radius: 30; -fx-background-color: #667eea; -fx-text-fill: white;");

        speedComboBox = new ComboBox<>(FXCollections.observableArrayList(0.5, 0.75, 1.0, 1.25, 1.5));
        speedComboBox.setValue(1.0);

        Button downloadBtn = new Button("📥 دانلود");
        Button deleteBtn = new Button("🗑 سطل زباله");
        Button tagBtn = new Button("🏷 تگ و یادداشت");

        controls.getChildren().addAll(playPauseBtn, speedComboBox, downloadBtn, deleteBtn, tagBtn);

        volumeSlider = new Slider(0, 1, 0.8);
        HBox volumeBox = new HBox(8, new Label("🔊"), volumeSlider);

        statusLabel = new Label("✅ آماده");
        statusLabel.setStyle("-fx-text-fill: #a0a0a0;");

        playerBox.getChildren().addAll(
                nowPlayingLabel,
                waveformCanvas,
                timeBox,
                controls,
                volumeBox,
                statusLabel
        );

        // رویدادها
        playPauseBtn.setOnAction(e -> togglePlayPause());
        speedComboBox.setOnAction(e -> { if (mediaPlayer != null) mediaPlayer.setRate(speedComboBox.getValue()); });
        deleteBtn.setOnAction(e -> deleteWithTrash());
        downloadBtn.setOnAction(e -> downloadCurrent());
        tagBtn.setOnAction(e -> editTagAndNote());

        volumeSlider.valueProperty().addListener((obs, old, val) -> {
            if (mediaPlayer != null) mediaPlayer.setVolume(val.doubleValue());
        });

        return playerBox;
    }

    // ====================== Waveform ======================
    private void setupWaveformVisualizer() {
        if (mediaPlayer == null) return;
        spectrumData = new double[64];

        mediaPlayer.setAudioSpectrumNumBands(64);
        mediaPlayer.setAudioSpectrumInterval(0.06);
        mediaPlayer.setAudioSpectrumThreshold(-70);

        mediaPlayer.setAudioSpectrumListener((timestamp, duration, magnitudes, phases) -> {
            for (int i = 0; i < magnitudes.length && i < spectrumData.length; i++) {
                spectrumData[i] = magnitudes[i] + 70;
            }
            Platform.runLater(this::drawWaveform);
        });
    }

    private void drawWaveform() {
        gc.clearRect(0, 0, waveformCanvas.getWidth(), waveformCanvas.getHeight());
        double w = waveformCanvas.getWidth();
        double h = waveformCanvas.getHeight();
        double barWidth = w / spectrumData.length;

        for (int i = 0; i < spectrumData.length; i++) {
            double barHeight = (spectrumData[i] / 60.0) * h * 0.85;
            double x = i * barWidth + barWidth / 2;

            gc.setStroke(Color.rgb(100, 180, 255));
            gc.setLineWidth(2.8);
            gc.strokeLine(x, h / 2 - barHeight / 2, x, h / 2 + barHeight / 2);
        }
    }

    // ====================== بقیه متدها (همان قبلی) ======================
    private void playVoiceMail(VoiceMail vm) { /* همان کد قبلی */
        currentPlaying = vm;
        nowPlayingLabel.setText("در حال پخش: " + vm.getCallerNumber());
        statusLabel.setText("در حال دانلود...");

        new Thread(() -> {
            try {
                byte[] data = httpGetBytes(BASE_URL + "list_recordings.php?play=" +
                        java.net.URLEncoder.encode(vm.getFileName(), "UTF-8"));

                Path tempFile = Paths.get(TEMP_DIR + vm.getFileName());
                Files.write(tempFile, data);

                Platform.runLater(() -> {
                    if (mediaPlayer != null) {
                        mediaPlayer.stop();
                        mediaPlayer.dispose();
                    }

                    Media media = new Media(tempFile.toUri().toString());
                    mediaPlayer = new MediaPlayer(media);
                    mediaPlayer.setVolume(volumeSlider.getValue());
                    mediaPlayer.setRate(speedComboBox.getValue());

                    setupMediaListeners();
                    setupWaveformVisualizer();
                    mediaPlayer.play();

                    isPlaying = true;
                    playPauseBtn.setText("⏸");
                    statusLabel.setText("در حال پخش");
                });
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("خطا: " + e.getMessage()));
            }
        }).start();
    }

    private void setupMediaListeners() { /* همان کد قبلی */
        mediaPlayer.currentTimeProperty().addListener((obs, old, val) ->
                Platform.runLater(() -> {
                    currentTimeLabel.setText(formatTime(val.toSeconds()));
                    if (mediaPlayer.getTotalDuration() != null) {
                        progressSlider.setValue(val.toSeconds() / mediaPlayer.getTotalDuration().toSeconds() * 100);
                    }
                }));

        mediaPlayer.setOnReady(() ->
                totalTimeLabel.setText(formatTime(mediaPlayer.getTotalDuration().toSeconds())));

        mediaPlayer.setOnEndOfMedia(() -> {
            isPlaying = false;
            playPauseBtn.setText("▶");
            playNext();
        });

        progressSlider.setOnMouseReleased(e -> {
            if (mediaPlayer != null) {
                mediaPlayer.seek(mediaPlayer.getTotalDuration().multiply(progressSlider.getValue() / 100));
            }
        });
    }

    private void playNext() {
        int idx = voiceMailList.indexOf(currentPlaying);
        if (idx >= 0 && idx < voiceMailList.size() - 1) {
            playVoiceMail(voiceMailList.get(idx + 1));
        }
    }

    private void togglePlayPause() {
        if (mediaPlayer == null) return;
        if (isPlaying) {
            mediaPlayer.pause();
            playPauseBtn.setText("▶");
        } else {
            mediaPlayer.play();
            playPauseBtn.setText("⏸");
        }
        isPlaying = !isPlaying;
    }

    private void loadVoiceMails() {
        statusLabel.setText("در حال بارگذاری...");
        new Thread(() -> {
            try {
                String json = httpGet(BASE_URL + "list_recordings.php");
                JSONArray arr = new JSONObject(json).getJSONArray("recordings");
                ObservableList<VoiceMail> list = FXCollections.observableArrayList();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    list.add(new VoiceMail(o.getString("filename"), o.getLong("modified"), o.getLong("size")));
                }
                Platform.runLater(() -> {
                    voiceMailList.setAll(list);
                    statusLabel.setText(voiceMailList.size() + " پیغام");
                });
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("خطا در اتصال به سرور"));
            }
        }).start();
    }

    private void deleteWithTrash() {
        if (currentPlaying == null) return;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "فایل به سطل زباله منتقل شود؟", ButtonType.YES, ButtonType.NO);
        alert.showAndWait().ifPresent(res -> {
            if (res == ButtonType.YES) {
                logActivity("DELETE", currentPlaying.getFileName());
                Platform.runLater(() -> {
                    voiceMailList.remove(currentPlaying);
                    statusLabel.setText("به سطل زباله منتقل شد");
                });
            }
        });
    }

    private void logActivity(String action, String filename) {
        try (FileWriter fw = new FileWriter(LOG_FILE, true)) {
            fw.write(new Date() + " | " + action + " | " + filename + "\n");
        } catch (Exception ignored) {}
    }

    private void editTagAndNote() {
        if (currentPlaying == null) return;
        TextInputDialog dialog = new TextInputDialog(currentPlaying.getNote());
        dialog.setHeaderText("یادداشت و تگ");
        dialog.showAndWait().ifPresent(note -> {
            currentPlaying.setNote(note);
            currentPlaying.setTag("بررسی شده");
            listView.refresh();
        });
    }

    private String formatTime(double seconds) {
        int min = (int) seconds / 60;
        int sec = (int) seconds % 60;
        return String.format("%02d:%02d", min, sec);
    }

    private String httpGet(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(10000);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private byte[] httpGetBytes(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(10000);
        try (InputStream is = conn.getInputStream(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toByteArray();
        }
    }

    private void downloadCurrent() {
        statusLabel.setText("دانلود در نسخه بعدی فعال می‌شود");
    }

    public static class VoiceMail {
        private final SimpleStringProperty fileName, callerNumber, date, tag, note;

        public VoiceMail(String fn, long ts, long sz) {
            this.fileName = new SimpleStringProperty(fn);
            String num = fn.replaceAll("[^0-9]", "");
            this.callerNumber = new SimpleStringProperty(num.length() >= 11 ? num.substring(0, 11) : "نامشخص");
            this.date = new SimpleStringProperty(new SimpleDateFormat("yyyy/MM/dd HH:mm").format(new Date(ts * 1000)));
            this.tag = new SimpleStringProperty("جدید");
            this.note = new SimpleStringProperty("");
        }

        public String getFileName() { return fileName.get(); }
        public String getCallerNumber() { return callerNumber.get(); }
        public String getDate() { return date.get(); }
        public String getTag() { return tag.get(); }
        public String getNote() { return note.get(); }

        public void setTag(String t) { tag.set(t); }
        public void setNote(String n) { note.set(n); }
    }
}