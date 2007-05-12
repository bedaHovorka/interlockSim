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


/**
 * Factory for editing context
 */
public interface EditingContextFactory extends ContextFactory {
    /**
     * create new context
     * @return empty context
     */
    public EditingContext createEmptyContext();
    
    /**
     * @param context
     * @param clazz
     * @param arguments
     * @return create new object in context
     * @throws Exception 
     */
    public Object createNew(EditingContext context, Class<?> clazz, Object... arguments) throws Exception;    
}
