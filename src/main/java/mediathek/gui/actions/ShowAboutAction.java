package mediathek.gui.actions;

import mediathek.mainwindow.MediathekGui;

import javax.swing.*;
import java.awt.event.ActionEvent;

public class ShowAboutAction extends AbstractAction {
    private final MediathekGui mediathekGui;

    public ShowAboutAction(MediathekGui mediathekGui) {
        this.mediathekGui = mediathekGui;
        putValue(Action.NAME,"Über dieses Programm...");
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        mediathekGui.showAboutDialog();
        /*
        Platform.runLater(() -> {
            Stage window = new Stage();
            window.setTitle("JavaFX About");
            window.setScene(new Scene(new AboutController()));
            window.setResizable(false);
            window.show();
        });
         */
    }
}
