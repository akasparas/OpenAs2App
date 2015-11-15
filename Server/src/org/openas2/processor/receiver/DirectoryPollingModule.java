package org.openas2.processor.receiver;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import javax.activation.DataHandler;
import javax.mail.MessagingException;
import javax.mail.internet.MimeBodyPart;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openas2.OpenAS2Exception;
import org.openas2.Session;
import org.openas2.WrappedException;
import org.openas2.message.FileAttribute;
import org.openas2.message.InvalidMessageException;
import org.openas2.message.Message;
import org.openas2.params.InvalidParameterException;
import org.openas2.params.MessageParameters;
import org.openas2.params.ParameterParser;
import org.openas2.partner.AS2Partnership;
import org.openas2.partner.Partnership;
import org.openas2.processor.resender.ResenderModule;
import org.openas2.processor.sender.SenderModule;
import org.openas2.util.ByteArrayDataSource;
import org.openas2.util.IOUtilOld;

public abstract class DirectoryPollingModule extends PollingModule {
    public static final String PARAM_OUTBOX_DIRECTORY = "outboxdir";
    public static final String PARAM_FILE_EXTENSION_FILTER = "fileextensionfilter";
    public static final String PARAM_ERROR_DIRECTORY = "errordir";
    public static final String PARAM_SENT_DIRECTORY = "sentdir";
    public static final String PARAM_FORMAT = "format";
    public static final String PARAM_DELIMITERS = "delimiters";
    public static final String PARAM_DEFAULTS = "defaults";
    public static final String PARAM_MIMETYPE = "mimetype";   
    public static final String PARAM_RESEND_MAX_RETRIES = "resend_max_retries";   
    private Map<String, Long> trackedFiles;

	private Log logger = LogFactory.getLog(DirectoryPollingModule.class.getSimpleName());

    
    public void init(Session session, Map<String, String> options) throws OpenAS2Exception {
        super.init(session, options);
        getParameter(PARAM_OUTBOX_DIRECTORY, true);
        getParameter(PARAM_ERROR_DIRECTORY, true);
    }

    public void poll() {
        try {
            // scan the directory for new files
            scanDirectory(getParameter(PARAM_OUTBOX_DIRECTORY, true));

            // update tracking info. if a file is ready, process it
            updateTracking();
        } catch (OpenAS2Exception oae) {
            oae.terminate();
            forceStop(oae);
        } catch (Exception e) {
            new WrappedException(e).terminate();
            forceStop(e);
        }
    }

    protected void scanDirectory(String directory) throws IOException, InvalidParameterException {
        File dir = IOUtilOld.getDirectoryFile(directory);
        String extensionFilter = getParameter(PARAM_FILE_EXTENSION_FILTER, "");

        // get a list of entries in the directory
        File[] files = extensionFilter.length()>0?IOUtilOld.getFiles(dir, extensionFilter):dir.listFiles();
        if (files == null) {
            throw new InvalidParameterException("Error getting list of files in directory", this,
                    PARAM_OUTBOX_DIRECTORY, dir.getAbsolutePath());
        }

        // iterator through each entry, and start tracking new files
        if (files.length > 0) {
            for (int i = 0; i < files.length; i++) {
                File currentFile = files[i];

                if (checkFile(currentFile)) {
                    // start watching the file's size if it's not already being watched
                    trackFile(currentFile);
                }
            }
        }
    }

    protected boolean checkFile(File file) {
        if (file.exists() && file.isFile()) {
            try {
                // check for a write-lock on file, will skip file if it's write locked
                FileOutputStream fOut = new FileOutputStream(file, true);
                fOut.close();
                return true;
            } catch (IOException ioe) {
                // a sharing violation occurred, ignore the file for now
            }
        }
        return false;
    }

    protected void trackFile(File file) {
        Map<String, Long> trackedFiles = getTrackedFiles();
        String filePath = file.getAbsolutePath();
        if (trackedFiles.get(filePath) == null) {
            trackedFiles.put(filePath, new Long(file.length()));
        }
    }

    protected void updateTracking() throws OpenAS2Exception {
        // clone the trackedFiles map, iterator through the clone and modify the original to avoid iterator exceptions
        // is there a better way to do this?
        Map<String, Long> trackedFiles = getTrackedFiles();
        Map<String, Long> trackedFilesClone = new HashMap<String, Long>(trackedFiles);

        for (Iterator<Map.Entry<String, Long>> it = trackedFilesClone.entrySet().iterator(); it.hasNext();) {
            // get the file and it's stored length
            Map.Entry<String, Long> fileEntry = it.next();
            File file = new File((String) fileEntry.getKey());
            long fileLength = ((Long) fileEntry.getValue()).longValue();

            // if the file no longer exists, remove it from the tracker
            if (!checkFile(file)) {
                trackedFiles.remove(fileEntry.getKey());
            } else {
                // if the file length has changed, update the tracker
                long newLength = file.length();
                if (newLength != fileLength) {
                    trackedFiles.put((String)fileEntry.getKey(), new Long(newLength));
                } else {
                    // if the file length has stayed the same, process the file and stop tracking it
                    try {
                        processFile(file);
                    } finally {
                        trackedFiles.remove(fileEntry.getKey());
                    }
                }
            }
        }
    }

    protected void processFile(File file) throws OpenAS2Exception {
    	
    	if (logger.isInfoEnabled()) logger.info("processing " + file.getAbsolutePath());

        Message msg = createMessage();
        msg.setAttribute(FileAttribute.MA_FILEPATH, file.getAbsolutePath());
        msg.setAttribute(FileAttribute.MA_FILENAME, file.getName());
        
        
        /* save the file name into message object, it will be stored into pending information file
        */ 
        msg.setAttribute(FileAttribute.MA_PENDINGFILE, file.getName());
        
        try {
            updateMessage(msg, file);
            if (logger.isInfoEnabled())
            	logger.info("file assigned to message " + file.getAbsolutePath() + msg.getLoggingText());

            if (msg.getData() == null) {
                throw new InvalidMessageException("No Data");
            }
            if (logger.isTraceEnabled()) logger.trace("PARTNERSHIP parms: " + msg.getPartnership().getAttributes());
            // Retry count - first try on partnership then directory polling module
            String maxRetryCnt = msg.getPartnership().getAttribute(AS2Partnership.PA_RESEND_MAX_RETRIES);
            if (maxRetryCnt == null || maxRetryCnt.length() < 1)
            {
            	maxRetryCnt = getSession().getProcessor().getParameters().get(PARAM_RESEND_MAX_RETRIES);
            }
            if (logger.isTraceEnabled()) logger.trace("RESEND COUNT EXTRACTED from config: " + maxRetryCnt);
            Map<Object, Object> options = msg.getOptions();
            options.put(ResenderModule.OPTION_RETRIES, maxRetryCnt);

            // Transmit the message
            getSession().getProcessor().handle(SenderModule.DO_SEND, msg, options);

               /*asynch mdn logic 2007-03-12
            	If the return status is pending in msg's attribute "status" then copy 
            	the transmitted file to pending folder and wait for the receiver to 
            	make another HTTP call to post AsyncMDN
            	*/ 
            String msgStatus = msg.getAttribute(FileAttribute.MA_STATUS);
            if (msgStatus != null && msgStatus.equals(FileAttribute.MA_PENDING)) {
				File pendingFile = null;
				try {
					pendingFile = new File(msg.getAttribute(FileAttribute.MA_PENDINGFILE));
					// Make sure the folder exists
					File parentFolder = pendingFile.getParentFile();
					if (parentFolder != null && !parentFolder.exists()) parentFolder.mkdirs();
					// Now copy the file over for MDN handler to manage
					IOUtilOld.copyFile(file, pendingFile);

					if (logger.isInfoEnabled())
						logger.info("copied " + file.getAbsolutePath()
							+ " to pending folder : "
							+ pendingFile.getAbsolutePath()+ msg.getLoggingText());

				} catch (IOException iose) {
					logger.error("Failed to copy file to pending folder: ", iose);
					OpenAS2Exception se = new OpenAS2Exception(
							"File was successfully sent but not copied to pending folder: "
									+ pendingFile);
					se.initCause(iose);
				}
			}  
            
            // If the Sent Directory option is set, move the transmitted file to
			// the sent directory
            String filePath = file.getAbsolutePath();
            if (getParameter(PARAM_SENT_DIRECTORY, false) != null) {
                File sentFile = null;

                try {
                    sentFile = new File(IOUtilOld.getDirectoryFile(getParameter(PARAM_SENT_DIRECTORY, true)), file
                            .getName());
                    sentFile = IOUtilOld.moveFile(file, sentFile, false, true);

                    if (logger.isInfoEnabled())
                    	logger.info("moved " + file.getAbsolutePath() + " to " + sentFile.getAbsolutePath()+ msg.getLoggingText());

                } catch (IOException iose) {
                	logger.error("Error moving file to sent folder: " + iose.getMessage(), iose);
                    OpenAS2Exception se = new OpenAS2Exception(
                            "File was successfully sent but not moved to sent folder: " + sentFile);
                    se.initCause(iose);
                }
            } else
            {
            	// Delete the file if a sent directory isn't set
            	// Try to get a reason to make debugging easier by using java.nio.file.Files.delete() instead of java.io.File.delete()
            	if (logger.isDebugEnabled()) logger.debug("Trying to delete file: " + file.getAbsolutePath());
            	Path path = file.toPath();
            	file = null;
            	try {
					Files.delete(path);
				} catch (IOException e) {
	                throw new OpenAS2Exception("File was successfully sent but not deleted: " + path.toAbsolutePath(), e);
				}
            }

            if (logger.isInfoEnabled()) logger.info("deleted " + filePath + msg.getLoggingText());

        } catch (OpenAS2Exception oae) {
        	if (logger.isInfoEnabled()) logger.info(oae.getLocalizedMessage()+ msg.getLoggingText());
            oae.addSource(OpenAS2Exception.SOURCE_MESSAGE, msg);
            oae.addSource(OpenAS2Exception.SOURCE_FILE, msg.getAttribute(FileAttribute.MA_FILEPATH));
            oae.terminate();
            if (file != null) IOUtilOld.handleError(file, getParameter(PARAM_ERROR_DIRECTORY, true));
        }
        

    }

    protected abstract Message createMessage();

    public void updateMessage(Message msg, File file) throws OpenAS2Exception {
        MessageParameters params = new MessageParameters(msg);

        String defaults = getParameter(PARAM_DEFAULTS, false);

        if (defaults != null) {
            params.setParameters(defaults);
        }

        String filename = file.getName();
        String format = getParameter(PARAM_FORMAT, false);

        if (format != null) {
            String delimiters = getParameter(PARAM_DELIMITERS, ".-");
            params.setParameters(format, delimiters, filename);
        }

        try {
            byte[] data = IOUtilOld.getFileBytes(file);
            String contentType = getParameter(PARAM_MIMETYPE, false);
            if (contentType == null) {
                contentType = "application/octet-stream";
            }
            else {
            	try {
            	contentType = ParameterParser.parse (contentType, params);
            	}
            	catch (InvalidParameterException e) {
            		if (logger.isInfoEnabled()) logger.error("Bad content-type" + contentType+ msg.getLoggingText());
            		contentType = "application/octet-stream";
            	}
            	}
            ByteArrayDataSource byteSource = new ByteArrayDataSource(data, contentType, null);
            MimeBodyPart body = new MimeBodyPart();
            body.setDataHandler(new DataHandler(byteSource));
            String encodeType = msg.getPartnership().getAttribute(Partnership.PA_CONTENT_TRANSFER_ENCODING);
            if (encodeType != null)
            	body.setHeader("Content-Transfer-Encoding", encodeType);
            else
            	body.setHeader("Content-Transfer-Encoding", "8bit"); // default is 8bit
            
            
//          below statement is not filename related, just want to make it  
//          consist with the parameter "mimetype="application/EDI-X12"" 
//          defined in config.xml   2007-06-01 
         
            body.setHeader("Content-Type", contentType);
            
//          add below statement will tell the receiver to save the filename 
//          as the one sent by sender. 2007-06-01
            String sendFileName = getParameter("sendfilename", false);
            if (sendFileName != null && sendFileName.equals("true")) {
            	body.setHeader("Content-Disposition", "Attachment; filename=\""+ 
            	msg.getAttribute(FileAttribute.MA_FILENAME) +"\"");
            	msg.setContentDisposition("Attachment; filename=\""+ 
            	msg.getAttribute(FileAttribute.MA_FILENAME) +"\"");
            }
          

            msg.setData(body);
        } catch (MessagingException me) {
            throw new WrappedException(me);
        } catch (IOException ioe) {
            throw new WrappedException(ioe);
        }

        // update the message's partnership with any stored information
        getSession().getPartnershipFactory().updatePartnership(msg, true);
        msg.updateMessageID();
    }

    public Map<String, Long> getTrackedFiles() {
        if (trackedFiles == null) {
            trackedFiles = new HashMap<String, Long>();
        }
        return trackedFiles;
    }
}