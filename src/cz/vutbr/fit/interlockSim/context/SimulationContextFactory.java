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
 * Factory for simulation context
 */
public interface SimulationContextFactory extends ContextFactory {
    /**
     * convert editing to simulation
     * @param editingContext 
     * @return simulation
     * @throws ContextCreationException if editing context is wrong
     */
    public SimulationContext createContext(EditingContext editingContext) throws ContextCreationException;
}
