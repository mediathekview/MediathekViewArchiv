package mediathek.gui.actions;

import com.formdev.flatlaf.icons.FlatHelpButtonIcon;
import javafx.application.Platform;
import mediathek.config.Konstanten;
import mediathek.mainwindow.MediathekGui;
import mediathek.tool.javafx.FXErrorDialog;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.net.URISyntaxException;

public class ShowOnlineHelpAction extends AbstractAction {
    public ShowOnlineHelpAction() {
        super();
        putValue(NAME, "Online-Hilfe anzeigen...");
        putValue(SMALL_ICON, new FlatHelpButtonIcon());
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        try {
            UrlHyperlinkAction.openURL(MediathekGui.ui(),Konstanten.ADRESSE_ONLINE_HELP);
        } catch (URISyntaxException ex) {
            Platform.runLater(() -> FXErrorDialog.showErrorDialog("Online-Hilfe",
                    "Fehler beim Öffnen der Online-Hilfe",
                    "Es trat ein Fehler beim Öffnen der Online-Hilfe auf.\nSollte dies häufiger auftreten kontaktieren Sie bitte das Entwicklerteam.",
                    ex));
        }
    }
}
