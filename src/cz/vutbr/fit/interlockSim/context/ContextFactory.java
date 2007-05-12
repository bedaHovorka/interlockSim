/* Brno University of Technology
 * Faculty of Information Technology
 * 
 * BSc Thesis	2006/2007
 * 
 * Railway Interlocking Simulator
 * 
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Context Factory Interface
 *
 */
public interface ContextFactory {
	/**
	 * create context from file
	 * @param file source 
	 * @return context
	 * @throws ContextCreationException if source is wrong
	 */
    public Context createContext(File file) throws ContextCreationException;
	
    /**
	 * create context from stream
	 * @param stream source 
	 * @return context
	 * @throws ContextCreationException if source is wrong
	 */
    public Context createContext(InputStream stream) throws ContextCreationException;
    
    /**
     * save context to file
     * @param context
     * @param file
     * @return if save was success
     */
    public boolean saveContext(Context context, File file);
    
    /**
     * save context to stream
     * @param context
     * @param stream
     * @return if save was success
     */
    public boolean saveContext(Context context, OutputStream stream);
}

