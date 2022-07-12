package mediathek.file;

import org.apache.commons.lang3.SystemUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * @author emil
 */
public class GetFile {

    public static final String PFAD_HILFETEXT_GEO = "/mediathek/file/hilfetext_geo.txt";
    public static final String PFAD_HILFETEXT_BLACKLIST = "/mediathek/file/hilfetext_blacklist.txt";
    public static final String PFAD_HILFETEXT_BEENDEN = "/mediathek/file/hilfetext_beenden.txt";
    public static final String PFAD_HILFETEXT_PRGRAMME = "/mediathek/file/hilfetext_pset.txt";
    public static final String PFAD_HILFETEXT_STANDARD_PSET = "hilfetext_standardPset.txt";
    public static final String PFAD_HILFETEXT_EDIT_DOWNLOAD_PROG = "hilfetext_editDownloadProg.txt";
    public static final String PFAD_HILFETEXT_RESET = "hilfetext_reset.txt";
    public static final String PFAD_HILFETEXT_RESET_SET = "hilfetext_reset_set.txt";
    public static final String PFAD_HILFETEXT_DIALOG_ADD_ABO = "hilfetext_dialog_add_abo.txt";
    private static final String PFAD_PSET_LINUX = "/mediathek/file/pset_linux.xml";
    private static final String PFAD_PSET_WINDOWS = "/mediathek/file/pset_windows.xml";
    private static final String PFAD_PSET_MAC = "/mediathek/file/pset_mac.xml";
    private static final Logger logger = LogManager.getLogger();

    public String getHilfeSuchen(String pfad) {
        StringBuilder ret = new StringBuilder();
        try (var is = Objects.requireNonNull(getClass().getResource(pfad)).openStream();
             InputStreamReader in = new InputStreamReader(is, StandardCharsets.UTF_8);
             BufferedReader br = new BufferedReader(in)) {
            String strLine;
            while ((strLine = br.readLine()) != null) {
                ret.append('\n').append(strLine);
            }
        } catch (IOException ex) {
            logger.error("getHilfeSuchen()", ex);
        }
        return ret.toString();
    }

    private static @NotNull String getPSetPath() {
        if (SystemUtils.IS_OS_LINUX)
            return PFAD_PSET_LINUX;
        else if (SystemUtils.IS_OS_MAC_OSX)
            return PFAD_PSET_MAC;
        else
            return PFAD_PSET_WINDOWS;
    }

    public static InputStreamReader getLocalPsetTemplate() {
        final String pfad = getPSetPath();
        try {
            return new InputStreamReader(Objects.requireNonNull(GetFile.class.getResource(pfad)).openStream(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            logger.error("getLocalPsetTemplate()",ex);
        }
        return null;
    }
}
