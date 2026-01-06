/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context;

import java.awt.Point;

import cz.vutbr.fit.interlockSim.objects.cells.NodeCell;
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock;

/**
 * Interface to shared functions of inner data model, which is allowed by editing
 *
 */
public interface EditingContext extends Context {
	/**
	 * put the cell into context, the cell must be {@link NodeCell}
	 * @param key
	 * @param cell
	 */
	public void putCell(Point key, NodeCell cell);

	/**
	 * remove cell from context
	 * @param key
	 */
	public void removeCell(Point key);

	/**
	 * @param from
	 * @param to
	 */
	public void moveCell(Point from, Point to);

	/**
	 * Remove {@link TrackBlock} from context
	 * @param block
	 */
	public void removeLine(TrackBlock block);

	/**
	 * @return current maximal speed, which is setting in elements
	 */
	public double getCurrentMaxSpeed();

	/**
	 * @param speed maximal speed, which is setting in elements
	 */
	public void setCurrentMaxSpeed(double speed);

	/**
	 * @return current track length, which is setting in elements
	 */
	public double getCurrentTrackLength();

	/**
	 * @param length track length, which is setting in elements
	 */
	public void setCurrentTrackLength(double length);

	/**
	 * Create relation between nodes. In specified places, must be {@link NodeCell}
	 * @param key1 location of first node
	 * @param key2 location of second node
	 * @param trackBlock block between nodes
	 */
	public void joinCells(Point key1, Point key2, TrackBlock trackBlock);

	/**
	 * @param name which is setting in elements
	 */
	public void setCurrentNameString(String name);

	/**
	 * @return name which is setting in elements
	 */
	public String getCurrentNameString();
}
