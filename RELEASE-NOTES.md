#              OpenAS2 Server
#              Version 2.6.2
#              RELEASE NOTES
-----
The OpenAS2 project is pleased to announce the release of OpenAS2 2.6.2

The release download file is: OpenAS2Server-2.6.2.zip

The zip file contains a PDF document (OpenAS2HowTo.pdf) providing information on installing and using the application.

Version 2.6.2 - 2018-09-26
This is a minor enhancement release:
       **IMPORTANT NOTE**: Please review upgrade notes below if you are upgrading

  1. Provide ability to use restricted headers in HTTP with partnership level overrides for the "Content-Transfer-Encoding" header
  2. Allow unsigned MDN to be sent using the "none" keyword for the "as2_mdn_options" attribute
  3. Use a Java 7 compatible method call for a Map class method that required Java 8

##Upgrade Notes
 See the openAS2HowTo appendix for the general process on upgrading OpenAS2.
 Below are some specific things to focus on depending on which version you are upgrading from.

 **You must review all notes for the relevant intermediate versions from your version to this release version.**

### If upgrading from versions older than 2.5.0:
      1. Change the name of the MDN sender module from "AsynchMDNSenderModule" to "MDNSenderModule" in the config.xml if using your existing config.xml file in the upgrade. If "AsynchMDNSenderModule" is not in the config then add the following: <module classname="org.openas2.processor.sender.MDNSenderModule" retries="3"/>
      2. Change the name of the partnership attribute "messageid" to "as2_message_id_format" if used in any partnership definition.
      3. Change the "as2_mdn_options" attribute to use $attribute.sign$ instead of hard coded signing algorithm
      4. If you experience issues with partners failing that were working in the previous version, check the troubleshooting section of the OpenAS2HowTo guide - specifically the issues around Content Transfer Encoding and Content Length/Chunking


### If upgrading from versions older than 2.4.1:
      1. If you have developed custom modules to enhance OpenAS2 they will need to be upgraded to include a healthcheck() method.
### If upgrading from versions older than 2.3.0:
      1. If using a custom startup script, re-integrate your customizations into the new script as the jar file for OpenAS2 is now tagged with its release version. (use asterisk [*] in classpath)
### If upgrading from versions older than 2.1.0:
      1. Add the new module to your existing config.xml (see classname="org.openas2.processor.msgtracking.DbTrackingModule" in release config.xml)
      2. If using a custom startup script, re-integrate your customizations into the new script
      3. As of 2.3.1 the log date format was changed to international standard. If you have log processing utilities that rely on a specific date format then you must change as needed (see documentation for mechanism)
  
Java 1.7 or later is required.

## NOTE FOR JAVA 1.5: No longer supported. Use a version prior to 2.2.0 to run on Java 1.5

## NOTE FOR JAVA 1.6: No longer supported as of version 2.5.0. For older versions of OpenAS2 see below
	The version of H2 database included in this release used for storing tracking messages will only support Java 1.7.
	If you do not need the DB tracking feature then simply remove it from the config.xml file.
	Otherwise:
		- download the older version of H2 that was compiled with support for Java 1.6 from this site:
			https://storage.googleapis.com/google-code-archive-downloads/v2/code.google.com/h2database/h2-2014-01-18.zip
		- Extract the file named h2-1.3.175.jar from the zip and replace the h2-1.4.192.jar in the "lib" folder with this one.
		- Change the startup script to include the replaced version of H2 jar in the classpath
		- Start OpenAS2 (required to run next statement successfully) and import the DDL (located in the config folder as db_ddl.sql) into the DB created by the older version like this:
			java -cp [path to OpenAS2 install]/lib/h2-1.3.175.jar org.h2.tools.RunScript -user sa -password OpenAS2 -url jdbc:h2:tcp://localhost:9092/openas2 -script [path to OpenAS2 install]/config/db_ddl.sql

## Historical list of changes: see the changes.txt file in the release package