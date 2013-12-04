/* 
 * Copyright 2009 Harvard University Library
 * 
 * This file is part of FITS (File Information Tool Set).
 * 
 * FITS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * FITS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with FITS.  If not, see <http://www.gnu.org/licenses/>.
 */
package edu.harvard.hul.ois.fits.tools.droid;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URL;

import org.jdom.Document;
import org.xml.sax.SAXException;


import edu.harvard.hul.ois.fits.Fits;
import edu.harvard.hul.ois.fits.exceptions.FitsToolException;
import edu.harvard.hul.ois.fits.tools.ToolBase;
import edu.harvard.hul.ois.fits.tools.ToolInfo;
import edu.harvard.hul.ois.fits.tools.ToolOutput;
import java.io.*;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.bind.JAXBException;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.tika.mime.MediaType;
import uk.gov.nationalarchives.droid.command.action.CommandExecutionException;
import uk.gov.nationalarchives.droid.container.ContainerSignatureDefinitions;
import uk.gov.nationalarchives.droid.container.ContainerSignatureSaxParser;
import uk.gov.nationalarchives.droid.core.BinarySignatureIdentifier;
import uk.gov.nationalarchives.droid.core.CustomResultPrinter;
import uk.gov.nationalarchives.droid.core.SignatureParseException;
import uk.gov.nationalarchives.droid.core.interfaces.IdentificationRequest;
import uk.gov.nationalarchives.droid.core.interfaces.IdentificationResult;
import uk.gov.nationalarchives.droid.core.interfaces.IdentificationResultCollection;
import uk.gov.nationalarchives.droid.core.interfaces.RequestIdentifier;
import uk.gov.nationalarchives.droid.core.interfaces.resource.FileSystemIdentificationRequest;
import uk.gov.nationalarchives.droid.core.interfaces.resource.RequestMetaData;

public class Droid extends ToolBase {

    public final static String xslt = Fits.FITS_HOME + "xml/droid/droid_to_fits.xslt";
    private boolean enabled = true;
    public static final String FILE_COLLECTION_NS = "http://www.nationalarchives.gov.uk/pronom/FileCollection";
    public static final String DROID_VERSION = "6.1.3";
    public static final String SIG_VERSION = "v70";
//    static final String CONTAINER_SIG_FILE = "droid/container-signature-20130501.xml";
    static final String CONTAINER_SIG_FILE = Fits.config.getString("droid_container_sigfile");
    // Set up DROID binary handler:
    private BinarySignatureIdentifier binarySignatureIdentifier;
    private ContainerSignatureDefinitions containerSignatureDefinitions;
    private static final String FORWARD_SLASH = "/";
    private static final String BACKWARD_SLASH = "\\";
    private CustomResultPrinter resultPrinter;
    private long maxBytesToScan = -1;
    boolean archives = false;
    // Options:
    /**
     * Set binarySignaturesOnly to disable container-based identification
     */
    private boolean binarySignaturesOnly = false;
    /**
     * Disable this flag to prevent the file extension being used as a format
     * hint
     */
    private boolean passFilenameWithInputStream = true;

    public Droid() throws FitsToolException {

        info = new ToolInfo("Droid", DROID_VERSION, null);

        try {
            String droid_conf = Fits.FITS_TOOLS + "droid" + File.separator;

            binarySignatureIdentifier = new BinarySignatureIdentifier();
            File fileSignaturesFile = new File(droid_conf + Fits.config.getString("droid_sigfile"));

            if (!fileSignaturesFile.exists()) {
                throw new CommandExecutionException("Signature file not found");
            }

            binarySignatureIdentifier.setSignatureFile(fileSignaturesFile.getAbsolutePath());
            try {
                binarySignatureIdentifier.init();
            } catch (SignatureParseException e) {
                throw new CommandExecutionException("Can't parse signature file");
            }
            binarySignatureIdentifier.setMaxBytesToScan(maxBytesToScan);
            String path = fileSignaturesFile.getAbsolutePath();
            String slash = path.contains(FORWARD_SLASH) ? FORWARD_SLASH : BACKWARD_SLASH;
            String slash1 = slash;

            // Set up container sig file:
            containerSignatureDefinitions = null;

            if (!StringUtils.isEmpty(CONTAINER_SIG_FILE)) {
                File containerSignaturesFile = new File(droid_conf + Fits.config.getString("droid_container_sigfile"));

                if (!containerSignaturesFile.exists()) {
                    throw new CommandExecutionException("Container signature file not found");
                }
                try {
                    final InputStream in = new FileInputStream(containerSignaturesFile.getAbsoluteFile());
                    final ContainerSignatureSaxParser parser = new ContainerSignatureSaxParser();
                    containerSignatureDefinitions = parser.parse(in);
                } catch (SignatureParseException e) {
                    throw new CommandExecutionException("Can't parse container signature file");
                } catch (IOException ioe) {
                    throw new CommandExecutionException(ioe);
                } catch (JAXBException jaxbe) {
                    throw new CommandExecutionException(jaxbe);
                }
            }
            resultPrinter =
                    new CustomResultPrinter(binarySignatureIdentifier, containerSignatureDefinitions,
                    "", slash, slash1, archives);
        } catch (Exception e) {
            throw new FitsToolException("Error initilizing DROID", e);
        }
    }

    @Override
    public ToolOutput extractInfo(File file) throws FitsToolException {
        long startTime = System.currentTimeMillis();
        // As this is a file, use the default number of bytes to inspect
        this.binarySignatureIdentifier.setMaxBytesToScan(this.maxBytesToScan);
        IdentificationResultCollection  results = null;
        // And identify:
        try {
            String fileName;
            try {
                fileName = file.getCanonicalPath();
            } catch (IOException e) {
                throw new CommandExecutionException(e);
            }
            URI uri = file.toURI();
            RequestMetaData metaData =
                    new RequestMetaData(file.length(), file.lastModified(), fileName);
            RequestIdentifier identifier = new RequestIdentifier(uri);
            identifier.setParentId(1L);

            InputStream in = null;
            IdentificationRequest request = new FileSystemIdentificationRequest(metaData, identifier);
            
            try {
                results = detect(request, new FileInputStream(file));
            } catch (IOException e) {
                throw new CommandExecutionException(e);
            } finally {
                IOUtils.closeQuietly(in);                
            }
        } catch (CommandExecutionException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }


        
//        IdentificationFile idFile = droid.identify(file.getPath());

        Document rawOut = createXml(results);
        Document fitsXml = transform(xslt, rawOut);

        /*
         * XMLOutputter outputter = new XMLOutputter(Format.getPrettyFormat());
         * try { outputter.output(fitsXml, System.out); } catch (IOException e)
         * { // TODO Auto-generated catch block e.printStackTrace(); }
         */

        output = new ToolOutput(this, fitsXml, rawOut);
        duration = System.currentTimeMillis() - startTime;
        runStatus = RunStatus.SUCCESSFUL;
        return output;
    }

    /**
     * Write the XML to the file, using the new schema format with elements for
     * most of the data.
     *
     * @throws FitsToolException
     * @throws SAXException
     */
    private Document createXml(IdentificationResultCollection idFile) throws FitsToolException {

        StringWriter out = new StringWriter();

        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        out.write("\n");
        out.write("<FileCollection xmlns=\"" + FILE_COLLECTION_NS + "\">");
        out.write("\n");
        out.write("  <DROIDVersion>" + DROID_VERSION + "</DROIDVersion>");
        out.write("\n");
        out.write("  <SignatureFileVersion>" + SIG_VERSION + "</SignatureFileVersion>");
        out.write("\n");
        out.write("  <DateCreated>" + 
//                AnalysisController.writeXMLDate(new java.util.Date()) + 
                "</DateCreated>");
        out.write("\n");

        //create IdentificationFile element and its attributes
        out.write("  <IdentificationFile ");
        out.write("IdentQuality=\"" + 
//                idFile.getClassificationText() +
                "\" ");
        out.write(">");
        out.write("\n");
        out.write("    <FilePath>" + idFile.getUri().getPath().replaceAll("&", "&amp;") + "</FilePath>");
        out.write("\n");
//        if (/*
//                 * saveResults &&
//                 */!"".equals(idFile.getWarning())) {
//            out.write("    <Warning>" + idFile.getWarning().replaceAll("&", "&amp;") + "</Warning>");
//            out.write("\n");
//        }

        if (idFile.getResults().size() == 0) {
            out.write("    <FileFormatHit>");
            out.write("\n");
            out.write("    </FileFormatHit>");
            out.write("\n");
        } else {
            //now create an FileFormatHit element for each hit
            for (IdentificationResult formatHit : idFile.getResults()) {
//                FileFormatHit formatHit = idFile.getHit(hitCounter);
                out.write("    <FileFormatHit>");
                out.write("\n");
                out.write("      <Status>" +"Information unavailable"  + "</Status>");
                out.write("\n");
                out.write("      <Name>" + formatHit.getName().replaceAll("&", "&amp;") + "</Name>");
                out.write("\n");
                if (formatHit.getVersion() != null) {
                    out.write("      <Version>" + formatHit.getVersion().replaceAll("&", "&amp;") + "</Version>");
                    out.write("\n");
                }
                if (formatHit.getPuid() != null) {
                    out.write("      <PUID>" + formatHit.getPuid().replaceAll("&", "&amp;") + "</PUID>");
                    out.write("\n");
                }
                if (formatHit.getMimeType() != null) {
                    out.write("      <MimeType>" + formatHit.getMimeType().replaceAll("&", "&amp;") + "</MimeType>");
                    out.write("\n");
                }
//                if (!"".equals(formatHit.getHitWarning())) {
//                    out.write("      <IdentificationWarning>"
//                            + formatHit.getHitWarning().replaceAll("&", "&amp;") + "</IdentificationWarning>");
//                    out.write("\n");
//                }
                out.write("    </FileFormatHit>");
                out.write("\n");
            }//end file hit FOR        
        }

        //close IdentificationFile element
        out.write("  </IdentificationFile>");
        out.write("\n");

        //close FileCollection element
        out.write("</FileCollection>");
        out.write("\n");

        out.flush();

        try {
            out.close();
        } catch (IOException e) {
            throw new FitsToolException("Error closing DROID XML output stream", e);
        }

        Document doc = null;
        try {
            doc = saxBuilder.build(new StringReader(out.toString()));
        } catch (Exception e) {
            throw new FitsToolException("Error parsing DROID XML Output", e);
        }
        return doc;
    }

    /**
     *
     * @param request
     * @param input
     * @return
     * @throws IOException
     * @throws CommandExecutionException
     */
    
    
    private IdentificationResultCollection detect(IdentificationRequest request, InputStream input) throws IOException, CommandExecutionException{
        request.open(input);
        IdentificationResultCollection results =
                binarySignatureIdentifier.matchBinarySignatures(request);
        if (this.isBinarySignaturesOnly()) {
            if (results.getResults().size() > 0) {
                return results;
            } 
        }
        // Also get container results:
        resultPrinter.print(results, request);

        results.addResult(resultPrinter.getResult());
        return results;
    }
    
    
    
    private MediaType determineMediaType(IdentificationRequest request, InputStream input) throws IOException, CommandExecutionException {
        request.open(input);
        IdentificationResultCollection results =
                binarySignatureIdentifier.matchBinarySignatures(request);
        //log.info("Got "+results.getResults().size() +" matches.");

        // Optionally, return top results from binary signature match only:
        if (this.isBinarySignaturesOnly()) {
            if (results.getResults().size() > 0) {
                return getMimeTypeFromResults(results.getResults());
            } else {
                return MediaType.OCTET_STREAM;
            }
        }

        // Also get container results:
        resultPrinter.print(results, request);

        // Return as a MediaType:
        return getMimeTypeFromResult(resultPrinter.getResult());
    }

    /**
     *
     * @param result
     * @return
     */
    protected static MediaType getMimeTypeFromResult(IdentificationResult result) {
        List<IdentificationResult> list = new ArrayList<IdentificationResult>();
        if (result != null) {
            list.add(result);
        }
        return getMimeTypeFromResults(list);
    }

    /**
     * TODO Choose 'vnd' Vendor-style MIME types over other options when there
     * are many in each Result. TODO This does not cope ideally with
     * multiple/degenerate Results. e.g. old TIFF or current RTF that cannot
     * tell the difference so reports no versions. If there are sigs that differ
     * more than this, this will ignore the versions.
     *
     * @param list
     * @return
     * @throws MimeTypeParseException
     */
    protected static MediaType getMimeTypeFromResults(List<IdentificationResult> results) {
        if (results == null || results.size() == 0) {
            return MediaType.OCTET_STREAM;
        }
        // Get the first result:
        IdentificationResult r = results.get(0);
        // Sort out the MIME type mapping:
        String mimeType = null;
        String mimeTypeString = r.getMimeType();
        if (mimeTypeString != null && !"".equals(mimeTypeString.trim())) {
            // This sometimes has ", " separated multiple types
            String[] mimeTypeList = mimeTypeString.split(", ");
            // Taking first (highest priority) MIME type:
            mimeType = mimeTypeList[0];
            // Fix case where no base type is supplied (e.g. "vnd.wordperfect"):
            if (mimeType.indexOf('/') == -1) {
                mimeType = "application/" + mimeType;
            }
        }
        // Build a MediaType
        MediaType mediaType = MediaType.parse(mimeType);
        Map<String, String> parameters = null;
        // Is there a MIME Type?
        if (mimeType != null && !"".equals(mimeType)) {
            parameters = new HashMap<String, String>(mediaType.getParameters());
            // Patch on a version parameter if there isn't one there already:
            if (parameters.get("version") == null
                    && r.getVersion() != null && (!"".equals(r.getVersion()))
                    && // But ONLY if there is ONLY one result.
                    results.size() == 1) {
                parameters.put("version", r.getVersion());
            }
        } else {
            parameters = new HashMap<String, String>();
            // If there isn't a MIME type, make one up:
            String id = "puid-" + r.getPuid().replace("/", "-");
            String name = r.getName().replace("\"", "'");
            // Lead with the PUID:
            mediaType = MediaType.parse("application/x-" + id);
            parameters.put("name", name);
            // Add the version, if set:
            String version = r.getVersion();
            if (version != null && !"".equals(version) && !"null".equals(version)) {
                parameters.put("version", version);
            }
        }

        return new MediaType(mediaType, parameters);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    /**
     * @return the binarySignaturesOnly
     */
    public boolean isBinarySignaturesOnly() {
        return binarySignaturesOnly;
    }

    /**
     * @param binarySignaturesOnly the binarySignaturesOnly to set
     */
    public void setBinarySignaturesOnly(boolean binarySignaturesOnly) {
        this.binarySignaturesOnly = binarySignaturesOnly;
    }
}
