package io.github.dsheirer.gui.playlist.web;

import io.github.dsheirer.web.SdrTrunkWebServer;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/** Playlist editor controls for the embedded web interface. */
public class WebInterfaceEditor extends VBox
{
    private final PasswordField mToken = new PasswordField();
    private final Label mStatus = new Label();

    public WebInterfaceEditor()
    {
        setPadding(new Insets(20));
        setSpacing(14);
        Label title = new Label("Web Interface");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        Label help = new Label("Manage browser access and restart the embedded web server. " +
            "The SDRTRUNK_WEB_TOKEN environment variable overrides this saved token.");
        help.setWrapText(true);

        mToken.setText(SdrTrunkWebServer.getSavedToken());
        mToken.setPromptText("Web access token");
        mToken.setPrefColumnCount(40);

        Button save = new Button("Save Token");
        save.setOnAction(event -> saveToken());
        Button restart = new Button("Restart Web Interface");
        restart.setOnAction(event -> restart());

        GridPane controls = new GridPane();
        controls.setHgap(10);
        controls.setVgap(10);
        controls.add(new Label("Access token:"), 0, 0);
        controls.add(mToken, 1, 0);
        controls.add(save, 0, 1);
        controls.add(restart, 1, 1);

        refreshStatus();
        getChildren().addAll(title, help, controls, mStatus,
            new Label("Web address: http://<this-computer-address>:8080/"));
    }

    private void saveToken()
    {
        String token = mToken.getText() == null ? "" : mToken.getText().trim();
        if(token.isBlank())
        {
            mStatus.setText("Token cannot be empty.");
            return;
        }
        SdrTrunkWebServer.saveToken(token);
        mStatus.setText("Token saved and applied. Browsers must reconnect using the new token.");
    }

    private void restart()
    {
        try
        {
            SdrTrunkWebServer.restartActive();
            mStatus.setText("Web interface restarted successfully.");
        }
        catch(Exception e)
        {
            mStatus.setText("Unable to restart: " + e.getMessage());
        }
    }

    private void refreshStatus()
    {
        mStatus.setText(SdrTrunkWebServer.isRunning() ? "Status: Running" : "Status: Stopped");
    }
}
