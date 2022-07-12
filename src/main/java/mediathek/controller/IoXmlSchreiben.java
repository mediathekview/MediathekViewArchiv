/*
 * MediathekView
 * Copyright (C) 2008 W. Xaver
 * W.Xaver[at]googlemail.com
 * http://zdfmediathk.sourceforge.net/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package mediathek.controller;

import mediathek.config.Daten;
import mediathek.config.Konstanten;
import mediathek.config.MVConfig;
import mediathek.daten.DatenDownload;
import mediathek.daten.DatenProg;
import mediathek.daten.DatenPset;
import mediathek.daten.abo.DatenAbo;
import mediathek.tool.ApplicationConfiguration;
import mediathek.tool.ReplaceList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

public class IoXmlSchreiben {
    private static final Logger logger = LogManager.getLogger(IoXmlSchreiben.class);
    private final XMLOutputFactory outFactory;

    public IoXmlSchreiben() {
        outFactory = XMLOutputFactory.newInstance();
    }

    private void writeFileHeader(XMLStreamWriter writer) throws XMLStreamException {
        writer.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
        writeNewLine(writer);
        writer.writeStartElement(Konstanten.XML_START);
        writeNewLine(writer);
    }

    private void writeFileEnd(XMLStreamWriter writer) throws XMLStreamException {
        writer.writeEndElement();
        writer.writeEndDocument();
        writer.flush();
        writer.close();
    }

    private void writeAbos(@NotNull XMLStreamWriter writer) throws XMLStreamException {
        writer.writeCharacters("\n\n");
        writeNewLine(writer);

        for (DatenAbo datenAbo : Daten.getInstance().getListeAbo()) {
            datenAbo.writeToConfig(writer);
        }
    }

    private void writeBlacklistRules(@NotNull XMLStreamWriter writer) throws XMLStreamException {
        writer.writeCharacters("\n\n");
        writeNewLine(writer);

        // remove duplicates
        var distinctBlacklistRules = Daten.getInstance().getListeBlacklist().stream().distinct().toList();
        for (var rule : distinctBlacklistRules) {
            rule.writeToConfig(writer);
        }
    }

    private void writeProgramSettings(XMLStreamWriter writer) throws XMLStreamException {
        writer.writeCharacters("\n\n");
        writer.writeCharacters("\n\n");
        writeNewLine(writer);
        xmlSchreibenConfig(writer, MVConfig.getSortedKVList());
        writeNewLine(writer);
    }

    private void writeProgramSets(XMLStreamWriter writer) throws XMLStreamException {
        writer.writeCharacters("\n\n");
        writeNewLine(writer);
        //Proggruppen schreiben, bei Konfig-Datei
        for (DatenPset datenPset : Daten.listePset) {
            xmlSchreibenDaten(writer, DatenPset.TAG, DatenPset.XML_NAMES, datenPset.arr, true);
            for (DatenProg datenProg : datenPset.getListeProg()) {
                xmlSchreibenDaten(writer, DatenProg.TAG, DatenProg.XML_NAMES, datenProg.arr, true);
            }
        }
    }

    private void writeReplacementTable(XMLStreamWriter writer) throws XMLStreamException {
        writer.writeCharacters("\n\n");
        //writer.writeComment("Ersetzungstabelle");
        writeNewLine(writer);

        for (String[] sa : ReplaceList.list) {
            xmlSchreibenDaten(writer, ReplaceList.REPLACELIST, ReplaceList.COLUMN_NAMES, sa, false);
        }
    }

    /**
     * Write all abo entries into XML config file.
     * @param writer the writer for the config file
     * @throws XMLStreamException caller must handle errors.
     */
    private void writeDownloads(XMLStreamWriter writer) throws XMLStreamException {
        /*
            CLI client must rely on specific format as this is some strange dialect.
            Here we set what version we save.
         */
        final int dl_list_version = 1;
        try {
            ApplicationConfiguration.getConfiguration().setProperty(ApplicationConfiguration.CLI_CLIENT_DOWNLOAD_LIST_FORMAT, dl_list_version);
        }
        catch (RejectedExecutionException ignore) {
            //this may occur during shutdown
        }
        catch (Exception e) {
            logger.error("writeDownloads error!", e);
        }

        writer.writeCharacters("\n\n");
        writeNewLine(writer);

        for (DatenDownload download : Daten.getInstance().getListeDownloads()) {
            if (download.isInterrupted()) {
                // unterbrochene werden gespeichert, dass die Info "Interrupt" erhalten bleibt
                download.writeConfigEntry(writer);
            } else if (!download.isFinished() && !download.isFromAbo()) {
                //Download, (Abo müssen neu angelegt werden)
                download.writeConfigEntry(writer);
            }
        }
    }

    private void xmlSchreibenPset(XMLStreamWriter writer, DatenPset[] psetArray) throws XMLStreamException {
        // wird beim Export Sets verwendet
        writer.writeCharacters("\n\n");
        for (DatenPset pset : psetArray) {
            xmlSchreibenDaten(writer, DatenPset.TAG, DatenPset.XML_NAMES, pset.arr, true);
            for (DatenProg datenProg : pset.getListeProg()) {
                xmlSchreibenDaten(writer, DatenProg.TAG, DatenProg.XML_NAMES, datenProg.arr, true);
            }
            writer.writeCharacters("\n\n");
        }
    }

    private void xmlSchreibenDaten(XMLStreamWriter writer, String xmlName, String[] xmlSpalten, String[] datenArray, boolean newLine) {
        final int xmlMax = datenArray.length;
        try {
            writer.writeStartElement(xmlName);
            if (newLine) {
                writeNewLine(writer);
            }
            for (int i = 0; i < xmlMax; ++i) {
                if (!datenArray[i].isEmpty()) {
                    if (newLine) {
                        writer.writeCharacters("\t"); //Tab
                    }
                    writer.writeStartElement(xmlSpalten[i]);
                    writer.writeCharacters(datenArray[i]);
                    writer.writeEndElement();
                    if (newLine) {
                        writeNewLine(writer);
                    }
                }
            }
            writer.writeEndElement();
            writeNewLine(writer);
        } catch (Exception ex) {
            logger.error("xmlSchreibenDaten", ex);
        }
    }

    private void writeNewLine(XMLStreamWriter writer) throws XMLStreamException {
        writer.writeCharacters("\n"); //neue Zeile
    }

    private void xmlSchreibenConfig(XMLStreamWriter writer, List<String[]> configValues) {
        try {
            writer.writeStartElement(MVConfig.SYSTEM);
            writeNewLine(writer);
            for (var xmlSpalte : configValues) {
                final var key = xmlSpalte[0];
                if (MVConfig.Configs.find(key)) {
                    //nur Configs schreiben die es noch gibt
                    writer.writeCharacters("\t"); //Tab
                    writer.writeStartElement(key);
                    writer.writeCharacters(xmlSpalte[1]);
                    writer.writeEndElement();
                    writeNewLine(writer);
                }
            }
            writer.writeEndElement();
            writeNewLine(writer);
        } catch (Exception ex) {
            logger.error(ex);
        }
    }

    public synchronized void writeConfigurationFile(Path xmlFilePath) {
        logger.debug("Daten Schreiben nach: {}", xmlFilePath.toString());
        xmlDatenSchreiben(xmlFilePath);
    }

    public synchronized void exportPset(DatenPset[] pSet, String datei) {
        final Path xmlFilePath = Paths.get(datei);
        try (OutputStream os = Files.newOutputStream(xmlFilePath);
             OutputStreamWriter out = new OutputStreamWriter(os, StandardCharsets.UTF_8)
        ) {
            XMLStreamWriter writer = outFactory.createXMLStreamWriter(out);
            logger.info("Pset exportieren nach: {}", xmlFilePath.toString());
            logger.debug("Start Schreiben nach: {}", xmlFilePath.toAbsolutePath());

            writeFileHeader(writer);

            xmlSchreibenPset(writer, pSet);

            writeFileEnd(writer);
            logger.debug("geschrieben!");
        } catch (Exception ex) {
            logger.error("nach {}", datei, ex);
        }
    }

    private void xmlDatenSchreiben(Path xmlFilePath) {
        logger.debug("Config Schreiben nach: {} startet", xmlFilePath.toAbsolutePath());

        try (OutputStream os = Files.newOutputStream(xmlFilePath);
             OutputStreamWriter out = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
            XMLStreamWriter writer = outFactory.createXMLStreamWriter(out);

            writeFileHeader(writer);

            writeAbos(writer);

            writeBlacklistRules(writer);

            writeProgramSettings(writer);

            writeProgramSets(writer);

            writeReplacementTable(writer);

            writeDownloads(writer);

            writer.writeCharacters("\n\n");

            writeFileEnd(writer);

            logger.debug("Config Schreiben beendet");
        } catch (Exception ex) {
            logger.error("xmlDatenSchreiben", ex);
        }
    }
}
