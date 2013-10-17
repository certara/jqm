
package com.enioka.jqm.tools;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;

@SuppressWarnings("serial")
public class Servlet extends HttpServlet {

	Logger jqmlogger = Logger.getLogger(this.getClass());

	public Servlet() {

	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) {

		String fileName = request.getParameter("file");
		File realFileName = new File("/Users/pico/Downloads/tests", fileName);
		FileInputStream fis = null;
		OutputStream out;

		try {
			out = response.getOutputStream();
			fis = new FileInputStream(realFileName);
			response.setContentType("application/octet-stream");

			IOUtils.copy(fis, out); // Copy bytes from an InputStream to an
			                        // OutputStream.

			IOUtils.closeQuietly(out); // Good practice
			IOUtils.closeQuietly(fis);

		} catch (FileNotFoundException e) {

			jqmlogger.info(e + "No file named: " + fileName + "found");
		} catch (IOException e) {

			jqmlogger.info(e);
		}
	}
}
