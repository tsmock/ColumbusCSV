// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.columbusCSV;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.zip.DataFormatException;

import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.gpx.GpxData;
import org.openstreetmap.josm.data.gpx.GpxLink;
import org.openstreetmap.josm.data.gpx.GpxTrack;
import org.openstreetmap.josm.data.gpx.WayPoint;
import org.openstreetmap.josm.io.IllegalDataException;
import org.openstreetmap.josm.tools.Logging;

/**
 * This class reads a native CSV of the Columbus V-900 data logger and converts
 * them into a GPX data layer. This class supports as well the simple as the
 * extended mode. By default, the V-900 runs in simple mode, which contains the
 * most important data like
 * <ol>
 * <li>Position (Longitude and Latitude)
 * <li>Date & Time
 * <li>Speed
 * <li>Heading
 * </ol>
 * Audio recordings are way points which references the audio file (.wav file).
 * Make sure that all audio files are in the same folder as the CSV file.
 * 
 * The extended mode contains additional data regarding GPS data quality like
 * all DOP and GPS mode.
 * 
 * To activate the extended mode, just put a file named <tt>config.txt</tt> on
 * the microSD card which has been shipped with the device.
 * 
 * Then change the content of the <tt>config.txt</tt> to <code>
 *  1,000,001,
notes:
1 Professional mode
000 Over-speed tag 
001 Spy mode timer
 *  </code>
 * 
 * @author Oliver Wieland &lt;oliver.wieland@online.de&gt;
 * 
 */
public class ColumbusCSVReader {
    public static final String AUDIO_WAV_LINK = "audio/wav";
    /* GPX tags not provided by the GPXReader class */
    private static final String VDOP_TAG = "vdop";
    private static final String HDOP_TAG = "hdop";
    private static final String PDOP_TAG = "pdop";
    private static final String ELEVATIONHEIGHT_TAG = "ele";
    private static final String COMMENT_TAG = "cmt";
    private static final String DESC_TAG = "desc";
    private static final String FIX_TAG = "fix";
    private static final String TYPE_TAG = "columbus:type";

    private static final String[] EMPTY_LINE = new String[] {};
    private static final String SEPS = ",";
    /* Lines to read before deciding on Columbus file yes/no */
    private static final int MAX_SCAN_LINES = 20;
    private static final int MIN_SCAN_LINES = 10;

    private int dopConversionErrors = 0;
    private int dateConversionErrors = 0;

    private int firstVoxNumber = -1, lastVoxNumber = -1;

    private final Map<String, WayPoint> voxFiles = new HashMap<>();
    private final Collection<Collection<WayPoint>> allTrackPts = new ArrayList<>();
    private final List<WayPoint> trackPts = new ArrayList<>();
    private final List<WayPoint> allWpts = new ArrayList<>();
    private String fileDir;

    /**
     * Transforms a Columbus V-900 CSV file into a JOSM GPX layer.
     * 
     * @param fileName The Columbus file to import.
     * @return GPX representation of Columbus track file.
     * @throws IOException
     * @throws IllegalDataException
     */
    public GpxData transformColumbusCSV(String fileName) throws IOException, IllegalDataException {
        if (fileName == null || fileName.length() == 0) {
            throw new IllegalArgumentException(
                "File name must not be null or empty");
        }
    
        // GPX data structures
        GpxData gpxData = new GpxData();
    
        File f = new File(fileName);
        fileDir = f.getParent();
        FileInputStream fstream = new FileInputStream(fileName);
        // Get the object of DataInputStream
        DataInputStream in = new DataInputStream(fstream);
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String strLine;
        // Initial values
        int line = 1;
        initImport();
        dropBufferLists();
    
        int waypts = 0, trkpts = 0, audiopts = 0, missaudio = 0, rescaudio = 0;
        try {
            // Read File Line By Line
            while ((strLine = br.readLine()) != null) {
                String[] csvFields = getCSVLine(strLine); // Get the columns of
                                      // the current line
                if (csvFields.length == 0 || line <= 1) { // Skip, if line is
                                      // header or contains
                                      // no data
                    ++line;
                    continue;
                }
    
                try {
                    WayPoint wpt = createWayPoint(csvFields, fileDir);
                    String wptType = (String) wpt.attr.get(TYPE_TAG);
                    String oldWptType = csvFields[1];
        
                    if ("T".equals(wptType)) { // point of track (T)
                        trackPts.add(wpt);
                        trkpts++;
                    } else { // way point (C) / have voice file: V)
                        if (!wptType.equals(oldWptType)) { // type changed?
                            if ("V".equals(oldWptType)) { // missing audiofile
                                missaudio++;
                            }
                            if ("C".equals(oldWptType)) { // rescued audiofile
                                rescaudio++;
                            }
                        } else {
                            if ("V".equals(wptType)) { // wpt with vox
                                audiopts++;
                            }
                        }
            
                        gpxData.waypoints.add(wpt); // add the waypoint to the track
                        waypts++;
                    }
        
                    allWpts.add(wpt);
        
                    wpt.attr.remove(TYPE_TAG);
                } catch (Exception ex) {
                    br.close();
                    throw new IllegalDataException(tr("Error in line " + line
                        + ": " + ex), ex);
                }
                ++line;
            }
        } finally {
            // Close the input stream
            br.close();
        }
    
        // do some sanity checks
        assert trackPts.size() == trkpts;
        assert gpxData.waypoints.size() == waypts;
        assert firstVoxNumber <= lastVoxNumber;
    
        rescaudio += searchForLostAudioFiles(gpxData);
    
        // compose the track
        allTrackPts.add(trackPts);
        GpxTrack trk = new GpxTrack(allTrackPts,
            Collections.emptyMap());
        gpxData.tracks.add(trk);
    
        assert gpxData.routes.size() == 1;
    
        // Issue conversion warning, if needed
        if (ColumbusCSVPreferences.warnConversion()
            && (dateConversionErrors > 0 || dopConversionErrors > 0)) {
            String message = String.format(
                "%d date conversion faults and %d DOP conversion errors",
                dateConversionErrors, dopConversionErrors);
            ColumbusCSVUtils.showWarningMessage(tr(message));
        }
        // Show summary
        if (ColumbusCSVPreferences.showSummary()) {
            showSummary(waypts, trkpts, audiopts, missaudio, rescaudio);
        }
    
        String desc = String.format(
            "Converted by ColumbusCSV plugin from track file '%s'",
            f.getName());
        gpxData.attr.put(GpxData.META_DESC, desc);
        gpxData.storageFile = f;
        return gpxData;
    }

    /**
     * Checks a (CSV) file for Columbus tags. This method is a simplified copy
     * of the @link transformColumbusCSV and just checks a small amount of
     * lines.
     * 
     * @param file The file to check.
     * @return true, if given file is a Columbus file; otherwise false.
     */
    public static boolean isColumbusFile(File file) throws IOException {
        if (file == null) return false; 
        
        FileInputStream fstream = new FileInputStream(file);
        // Get the object of DataInputStream
        DataInputStream in = new DataInputStream(fstream);
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String strLine;
        // Initial values
        int line = 0;
        int columbusLines = 0;
        try {
            // Read File Line By Line until we either exceed the maximum scan
            // lines or we are sure that we have a columbus file
            while ((strLine = br.readLine()) != null
                && (line < MAX_SCAN_LINES || columbusLines > MIN_SCAN_LINES)) {
                String[] csvFields = getCSVLine(strLine); // Get the columns of
                                      // the current line
                ++line;
                if (csvFields.length == 0 || line <= 1) { // Skip, if line is
                                      // header or contains
                                      // no data
                    continue;
                }
        
                String wptType = csvFields[1];
                // Check for columbus tag
                if ("T".equals(wptType) || "V".equals(wptType)
                    || "C".equals(wptType)) {
                    // ok, we found one line but still not convinced ;-)
                    columbusLines++;
                }
            }
        } finally {
            // Close the input stream
            br.close();
        }
    
        return columbusLines > MIN_SCAN_LINES;
    }

    /**
     * Searches for unlinked audio files and tries to link them with the closest
     * way point. This requires that the file date of the wav files is kept -
     * there is no other way to assign the audio files to way points.
     * 
     * @param gpx
     * @return
     */
    private int searchForLostAudioFiles(GpxData gpx) {
        Map<String, WayPoint> voxFiles = getVoxFileMap();
    
        int first, last;
        first = getFirstVoxNumber();
        last = getLastVoxNumber();
    
        int rescuedFiles = 0;
    
        for (int i = first; i < last; i++) {
            String voxFile = String.format("vox%05d.wav", i);
            String nextVoxFile = String.format("vox%05d.wav", i + 1);
            if (!voxFiles.containsKey(voxFile)) {
                Logging.info("Found lost vox file " + voxFile);
        
                File f = getVoxFilePath(voxFile);
                WayPoint nearestWpt = null;
                List<WayPoint> wpts = getAllWayPoints();
                // Attach recording to the way point right before the next vox
                // file
                if (voxFiles.containsKey(nextVoxFile)) {
                    WayPoint nextWpt = voxFiles.get(nextVoxFile);
                    int idx = getAllWayPoints().indexOf(nextWpt) - 5;
                    if (idx >= 0) {
                        nearestWpt = wpts.get(idx);
                    } else {
                        nearestWpt = wpts.get(0);
                    }
                } else { // attach to last way point
                    nearestWpt = wpts.get(wpts.size() - 1);
                }
        
                // Add link to found way point
                if (nearestWpt != null) {
                    if (addLinkToWayPoint(nearestWpt, "*" + voxFile + "*", f)) {
                        Logging.info(String.format(
                            "Linked file %s to position %s", voxFile,
                            nearestWpt.getCoor().toDisplayString()));
                        // Add linked way point to way point list of GPX; otherwise it would not be shown correctly
                        gpx.waypoints.add(nearestWpt);
                        rescuedFiles++;
                    } else {
                        Logging.error(String.format("Could not link vox file %s due to invalid parameters.", voxFile));
                    }
                }
            }
        }
    
        return rescuedFiles;
    }

    /**
     * 
     */
    private void initImport() {
        dateConversionErrors = 0;
        dopConversionErrors = 0;
        firstVoxNumber = Integer.MAX_VALUE;
        lastVoxNumber = Integer.MIN_VALUE;
    }

    /**
     * Clears all temporary buffers.
     */
    void dropBufferLists() {
        allTrackPts.clear();
        trackPts.clear();
        voxFiles.clear();
    }

    /**
     * Shows the summary to the user.
     * 
     * @param waypts
     *            The number of imported way points
     * @param trkpts
     *            The number of imported track points
     * @param audiopts
     *            The number of imported way points with vox
     * @param missaudio
     *            The number of missing audio files
     * @param rescaudio
     *            The number of rescued audio files
     */
    private void showSummary(int waypts, int trkpts, int audiopts,
        int missaudio, int rescaudio) {
        String message = "";
        if (missaudio > 0) {
            message = String
                .format("Imported %d track points and %d way points (%d with audio, %d rescued).%n"+
                        "Note: %d audio files could not be found, please check marker comments!",
                    trkpts, waypts, audiopts, rescaudio, missaudio);
        } else {
            message = String
                .format("Imported %d track points and %d way points (%d with audio, %d rescued).",
                    trkpts, waypts, audiopts, rescaudio);
        }
        ColumbusCSVUtils.showInfoMessage(tr(message));
    }

    /**
     * Creates a GPX way point from a tokenized CSV line. The attributes of the
     * way point depends on whether the Columbus logger runs in simple or
     * professional mode.
     * 
     * @param csvLine
     *            The columns of a single CSV line.
     * @return The corresponding way point instance.
     * @throws DataFormatException
     */
    private WayPoint createWayPoint(String[] csvLine, String fileDir) throws IOException {
        // Sample line in simple mode
        // INDEX,TAG,DATE,TIME,LATITUDE N/S,LONGITUDE
        // E/W,HEIGHT,SPEED,HEADING,VOX
        // 1,T,090430,194134,48.856330N,009.089779E,318,20,0,
    
        // Sample line in extended mode
        // INDEX,TAG,DATE,TIME,LATITUDE N/S,LONGITUDE
        // E/W,HEIGHT,SPEED,HEADING,FIX MODE,VALID,PDOP,HDOP,VDOP,VOX
        // 1,T,090508,191448,48.856928N,009.091153E,330,3,0,3D,SPS ,1.4,1.2,0.8,
        if (csvLine.length != 10 && csvLine.length != 15)
            throw new IOException("Invalid number of tokens: " + csvLine.length);
        boolean isExtMode = csvLine.length > 10;
    
        // Extract latitude/longitude first
        String lat = csvLine[4];
        double latVal = Double.parseDouble(lat.substring(0, lat.length() - 1));
        if (lat.endsWith("S")) {
            latVal = -latVal;
        }
    
        String lon = csvLine[5];
        double lonVal = Double.parseDouble(lon.substring(0, lon.length() - 1));
        if (lon.endsWith("W")) {
            lonVal = -lonVal;
        }
        LatLon pos = new LatLon(latVal, lonVal);
        WayPoint wpt = new WayPoint(pos);
    
        // set wpt type
        wpt.attr.put(TYPE_TAG, csvLine[1]);
    
        // Check for audio file and link it, if present
        String voxFile = null;
        if (isExtMode) {
            voxFile = csvLine[14];
        } else {
            voxFile = csvLine[9];
        }
    
        if (!ColumbusCSVUtils.isStringNullOrEmpty(voxFile)) {
            voxFile = voxFile + ".wav";
            File file = getVoxFilePath(fileDir, voxFile);
            if (file != null && file.exists()) {
                // link vox file
                int voxNum = getNumberOfVoxfile(voxFile);
                lastVoxNumber = Math.max(voxNum, lastVoxNumber);
                firstVoxNumber = Math.min(voxNum, firstVoxNumber);
        
                addLinkToWayPoint(wpt, voxFile, file);
        
                if (!"V".equals(csvLine[1])) {
                    Logging.info("Rescued unlinked audio file " + voxFile);
                }
                voxFiles.put(voxFile, wpt);
        
                // set type to way point with vox
                wpt.attr.put(TYPE_TAG, "V");
            } else { // audio file not found -> issue warning
            	Logging.error("File " + voxFile + " not found!");
                String warnMsg = tr("Missing audio file") + ": " + voxFile;
                Logging.error(warnMsg);
                if (ColumbusCSVPreferences.warnMissingAudio()) {
                    ColumbusCSVUtils.showInfoMessage(warnMsg);
                }
                wpt.attr.put(ColumbusCSVReader.COMMENT_TAG, warnMsg);
                // set type to ordinary way point
                wpt.attr.put(TYPE_TAG, "C");
            }
        }
    
        // Extract date/time
        SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyMMdd/HHmmss");
    
        try {
            wpt.setInstant(sdf.parse(csvLine[2] + "/" + csvLine[3]).toInstant());
        } catch (ParseException ex) {
            dateConversionErrors++;
            Logging.error(ex);
        }
    
        // Add further attributes
        // Elevation height (altitude provided by GPS signal)
        wpt.attr.put(ColumbusCSVReader.ELEVATIONHEIGHT_TAG, csvLine[6]);
    
        // Add data of extended mode, if applicable
        if (isExtMode && !ColumbusCSVPreferences.ignoreDOP()) {
            addExtendedGPSData(csvLine, wpt);
        }
    
        return wpt;
    }

    /**
     * Gets the full path of the audio file. Same as
     * <code>getVoxFilePath(getWorkingDirOfImport(), voxFile)</code>.
     * 
     * @param voxFile
     *            The name of the audio file without dir and extension.
     * @return
     * 
     */
    public File getVoxFilePath(String voxFile) {
        return getVoxFilePath(getWorkingDirOfImport(), voxFile);
    }

    /**
     * Gets the full path of the audio file.
     * 
     * @param fileDir
     *            The directory containing the audio file.
     * @param voxFile
     *            The name of the audio file without dir and extension.
     * @return
     */
    public File getVoxFilePath(String fileDir, String voxFile) {
        // The FAT16 file name is interpreted differently from case-sensitive
        // file systems, so we have to test several variants
        String[] fileNameVariants = new String[] { voxFile,
            voxFile.toLowerCase(), voxFile.toUpperCase() };
    
        for (int i = 0; i < fileNameVariants.length; i++) {
            File file = new File(fileDir + File.separator + fileNameVariants[i]);
            if (file.exists()) {
                return file;
            }
        }
        return null; // give up...
    }

    /**
     * Adds extended GPS data (*DOP and fix mode) to the way point
     * 
     * @param csvLine
     * @param wpt
     */
    private void addExtendedGPSData(String[] csvLine, WayPoint wpt) {
        // Fix mode
        wpt.attr.put(FIX_TAG, csvLine[9].toLowerCase());
    
        Float f;
        // Position errors (dop = dilution of position)
        f = ColumbusCSVUtils.floatFromString(csvLine[11]);
        if (!Float.isNaN(f)) {
            wpt.attr.put(ColumbusCSVReader.PDOP_TAG, f);
        } else {
            dopConversionErrors++;
        }
    
        f = ColumbusCSVUtils.floatFromString(csvLine[12]);
        if (!Float.isNaN(f)) {
            wpt.attr.put(ColumbusCSVReader.HDOP_TAG, f);
        } else {
            dopConversionErrors++;
        }
    
        f = ColumbusCSVUtils.floatFromString(csvLine[13]);
        if (!Float.isNaN(f)) {
            wpt.attr.put(ColumbusCSVReader.VDOP_TAG, f);
        } else {
            dopConversionErrors++;
        }
    }

    /**
     * Adds a link to a way point.
     * 
     * @param wpt
     *            The way point to add the link to.
     * @param voxFile
     * @param file
     * @return True, if link has been added; otherwise false
     */
    public boolean addLinkToWayPoint(WayPoint wpt, String voxFile, File file) {
        if (file == null || wpt == null || voxFile == null)
            return false;
    
        GpxLink lnk = new GpxLink(file.toURI().toString());
        lnk.type = ColumbusCSVReader.AUDIO_WAV_LINK;
        lnk.text = voxFile;
    
        // JOSM expects a collection of links here...
        Collection<GpxLink> linkList = new ArrayList<>(1);
        linkList.add(lnk);
    
        wpt.attr.put(GpxData.META_LINKS, linkList);
        wpt.attr.put(ColumbusCSVReader.COMMENT_TAG, "Audio recording");
        wpt.attr.put(ColumbusCSVReader.DESC_TAG, voxFile);
        return true;
    }

    /**
     * Splits a line of the CSV files into it's tokens.
     * 
     * @param line
     * @return Array containing the tokens of the CSV file.
     */
    private static String[] getCSVLine(String line) {
        if (line == null || line.length() == 0)
            return EMPTY_LINE;
    
        StringTokenizer st = new StringTokenizer(line, SEPS, false);
        int n = st.countTokens();
    
        String[] res = new String[n];
        for (int i = 0; i < n; i++) {
            res[i] = st.nextToken().trim();
        }
        return res;
    }

    /**
     * Extracts the number from a VOX file name, e. g. for a file named
     * "VOX01524" this method will return 1524.
     * 
     * @param fileName
     *            The vox file name.
     * @return The number of the vox file or -1; if the given name was not
     *         valid.
     */
    private int getNumberOfVoxfile(String fileName) {
        if (fileName == null)
            return -1;
    
        try {
            String num = fileName.substring(3);
            return Integer.parseInt(num);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Return the number of date conversion errors.
     * 
     * @return
     */
    public int getNumberOfDateConversionErrors() {
        return dateConversionErrors;
    }

    /**
     * Return the number of pdop/vdop/hdop conversion errors.
     * 
     * @return
     */
    public int getNumberOfDOPConversionErrors() {
        return dopConversionErrors;
    }

    /**
     * Gets the number of first vox file.
     * 
     * @return
     */
    public int getFirstVoxNumber() {
        return firstVoxNumber;
    }

    /**
     * Gets the number of last vox file.
     * 
     * @return
     */
    public int getLastVoxNumber() {
        return lastVoxNumber;
    }

    /**
     * Gets the map containing the vox files with their associated way point.
     * 
     * @return
     */
    public Map<String, WayPoint> getVoxFileMap() {
        return voxFiles;
    }

    /**
     * Gets the list containing all imported track and way points.
     * 
     * @return
     */
    public List<WayPoint> getAllWayPoints() {
        return allWpts;
    }

    /**
     * Gets the import directory.
     * 
     * @return
     */
    public String getWorkingDirOfImport() {
        return fileDir;
    }
}
