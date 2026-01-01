/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim;

import java.io.File;
import java.io.InputStream;
import java.util.ListResourceBundle;

/**
 * Resources
 */
public class MyResourceBoundle extends ListResourceBundle {
	private static final MyResourceBoundle instance = new MyResourceBoundle();
	private static final String BASE = "resource" + File.separatorChar;
	private MyResourceBoundle() {
		//EMPTY
	}

	@Override
	protected Object[][] getContents() {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * @return schema file for validation
	 */
	public InputStream getSchema() {
		return getFile("data.xsd");
	}

	/**
	 * @param name
	 * @return file from resource
	 */
	public InputStream getFile(String name) {
		return getClass().getResourceAsStream(BASE + name);
	}

	/**
	 * @return singleton instance
	 */
	public static MyResourceBoundle getInstance() {
		return instance;
	}
}
