/* Brno University of Technology
 * Faculty of Information Technology
 * 
 * BSc Thesis	2006/2007
 * 
 * Railway Interlocking Simulator
 * 
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.cells;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;
import java.util.Map.Entry;

import cz.vutbr.fit.interlockSim.sim.PathSeparatorChangeException;
import cz.vutbr.fit.interlockSim.util.EnumUnorientedGraph;

/**
 * Switch 
 * CZ: "výhybka"
 */
public class RailSwitch extends NodeCell {
	private enum Kind {
		/**
		 * switch with one branch
		 */
		SIMPLE(EnumSet.of(Conf.BRANCH, Conf.MAIN)),
		/**
		 * not specified
		 */
		DUAL(EnumSet.noneOf(Conf.class)); //NOT IMLEMENTED YET - EXTENSION
		
		private final Set<Conf> possibleConfs;

		private Kind(final Set<Conf> possibleConfs) {
			this.possibleConfs = Collections.unmodifiableSet(possibleConfs);
		}
		
		Set<Conf> getPossibleConfs() {
			return possibleConfs;
		}
	}
   
	protected enum Conf {
   		/**
   		 * main directon
   		 */
   		MAIN,
   		/**
   		 * branch direction
   		 */
   		BRANCH;
	}
   
	/**
	 * 
	 * Type "smer odbocky"
	 *
	 */
	public enum Type {
		/**
		 * 
		 */
		SIMPLE_RIGHT_FALSE(false),
		/**
		 * 
		 */
		SIMPLE_RIGHT_TRUE(true),
		/**
		 * 
		 */
		SIMPLE_LEFT_FALSE(false),
		/**
		 * 
		 */
		SIMPLE_LEFT_TRUE(true);
        
		private final boolean mergingPosition;
		private final Kind kind;
        
		/**
		 * merging orientation is in antidirection segment !!!
		 * @param branchDirection
		 * @param mergingOrientation - this means same as orientation by {@link OrientedNodeCell}
		 */
		Type (final boolean mergingOrientation) {
			//cosructor only for simple kinds
			this.mergingPosition = mergingOrientation;
			this.kind = Kind.SIMPLE;
		}
	    	   
	   	/**
	   	 * @return merging position (by simple kind)
	   	 */
	   	public boolean getMergingPosition() {
		   return mergingPosition;
	   	}
	   	
	   	Kind getKind() {
			return kind;
		}
	}
	
	protected static class SwitchMap<V> extends EnumMap<Type, EnumMap<SpatialType, V>> {
		protected SwitchMap() {
			super(Type.class);
		}
		
		protected void put(Type t, SpatialType st, V v) {
			EnumMap<SpatialType, V> subMap = get(t);
			if (subMap == null) {
				subMap = new EnumMap<SpatialType, V>(SpatialType.class);
				put(t, subMap);
			}
			subMap.put(st, v);
		}
		
		protected V get(Type t, SpatialType st) {
			final EnumMap<SpatialType, V> subMap = get(t);
			if (subMap == null) return null;
			return subMap.get(st);
		}
	}
	
	/**
	 * allowed speed 
	 */
	public static final int COMMON_BRANCH_SPEED = 13;
	/**
	 * allowed speed 
	 */
	public static final int COMMON_MAIN_SPEED = 30;
	/**
	 * 
	 */
	public static final String UNSUPORTED_SWITCH_TYPES_MESSAGE = "unsuported switch types";
	/**
	 * 
	 */
	public static final SpatialType[] SUPPORTED_SIMPLE_SPATIAL_TYPES = {SpatialType.HORIZONTAL, SpatialType.VERTICAL};
	private static final SwitchMap<Set<Segment>> branches = new SwitchMap<Set<Segment>>();
	private static final SwitchMap<EnumUnorientedGraph<Segment, Conf>> allConfs = new SwitchMap<EnumUnorientedGraph<Segment, Conf>>();
		
	static {
		putBranches(Type.SIMPLE_LEFT_FALSE, SpatialType.HORIZONTAL, Segment.E);
		putBranches(Type.SIMPLE_LEFT_FALSE, SpatialType.VERTICAL, Segment.G);
		putBranches(Type.SIMPLE_LEFT_TRUE, SpatialType.HORIZONTAL, Segment.D);
		putBranches(Type.SIMPLE_LEFT_TRUE, SpatialType.VERTICAL, Segment.B);
		
		putBranches(Type.SIMPLE_RIGHT_FALSE, SpatialType.HORIZONTAL, Segment.G);
		putBranches(Type.SIMPLE_RIGHT_FALSE, SpatialType.VERTICAL, Segment.D);
		putBranches(Type.SIMPLE_RIGHT_TRUE, SpatialType.HORIZONTAL, Segment.B);
		putBranches(Type.SIMPLE_RIGHT_TRUE, SpatialType.VERTICAL, Segment.E);
		
		for (Type t : Type.values()) {
			if (t.getKind() == Kind.SIMPLE) {
				for (SpatialType st : SUPPORTED_SIMPLE_SPATIAL_TYPES) {
					final EnumUnorientedGraph<Segment, Conf> confs = new EnumUnorientedGraph<Segment, Conf>(Segment.class);
					final Segment merging = st.getSegments()[t.getMergingPosition() ? 0 : 1]; //je v proti-directionu
			    	final Segment mainDir = Segment.anti(merging);
			    	final Set<Segment> set = branches.get(t, st); assert set.size() == 1 : set;
					final Segment branch = set.iterator().next();
			    	confs.put(merging, branch, Conf.BRANCH);
			    	confs.put(merging, mainDir, Conf.MAIN);
			    	allConfs.put(t, st, confs);
				}
			}
		}
		
	}
	
	private final EnumMap<Conf, Double> speeds = new EnumMap<Conf, Double>(Conf.class);
	private final EnumUnorientedGraph<Segment, Conf> confs;
    private final Type type;
    private Conf conf = Conf.MAIN;
   
    /**
      * @param spatialType
      * @param type
      * @param mainSpeed 
      * @param branchSpeed 
      */
	public RailSwitch(SpatialType spatialType, Type type, double mainSpeed, double branchSpeed) {
		super(spatialType);
		this.confs = allConfs.get(type, spatialType);
		if (this.confs == null) {
			throw new IllegalArgumentException(UNSUPORTED_SWITCH_TYPES_MESSAGE);
		}
		this.type = type;
		speeds.put(Conf.BRANCH, branchSpeed);
		speeds.put(Conf.MAIN, mainSpeed);
	}
	
	/**
	 * @param spatialType
	 * @param type
	 */
	public RailSwitch(SpatialType spatialType, Type type) {
		this(spatialType, type, COMMON_MAIN_SPEED, COMMON_BRANCH_SPEED);
	}
	
	/**
	 * 
	 * @return type
	 */
	public Type getType() {
	    return type;
	}

	protected Conf getConf() {
		return conf;
	}

	protected void setConf(Conf conf) {
		this.conf = conf;
	}
	
	/**
	 * switch to second configuration
	 */
	public void changeConf() {
		assert conf != null;
		conf = conf==Conf.MAIN ? Conf.BRANCH : (conf==Conf.BRANCH ? Conf.MAIN : Conf.MAIN);
	}
		
	public void cancelPathSetup(Segment from, Segment to) throws PathSeparatorChangeException {
		final Conf pathConf = getPathConfWithException(from, to);
		if (pathConf != getConf()) 
			throw new PathSeparatorChangeException("cancelPathSetup: Switch is not configured (neccesarry except?)", this);
	}
	
	public double allowedSpeed() {
		final Double double1 = speeds.get(getConf());
		assert double1 != null : speeds;
		return double1.doubleValue();
	}
	
	public Set<Segment> joins() {
    	final Set<Segment> joins = joinsOnLine();
    	joins.addAll(getBranchSegments());
    	return joins;
    }

	@Override
	public Segment getFollowingSegment(Segment from) {
		for (Entry<Segment, Conf> e : confs.getJoinedNodesAndEdges(from).entrySet()) {
			if (e.getValue() == getConf()) return e.getKey();
		}
		return null;
	}
	
	protected static Set<Segment> getBranchSegments(RailSwitch rSwitch) {
		return branches.get(rSwitch.getType(), rSwitch.getSpatialType());
	}
	
	/** 
	 * @return directions of branchs
	 */
	public Set<Segment> getBranchSegments() {
	    return getBranchSegments(this);	    
	}

	public void setUpPath(Segment from, Segment to, double allowedSpeed) throws PathSeparatorChangeException {
		setConf(getPathConfWithException(from, to));
	}

	private Conf getPathConfWithException(Segment from, Segment to) throws PathSeparatorChangeException {
		final Conf pathConf = confs.get(from, to);
		if (pathConf == null) throw new PathSeparatorChangeException("switch doesn't join this segments", this);
		return pathConf;
	}

	public Set<Segment> possibleFollowers(Segment from) {
		return confs.getJoinedNodesAndEdges(from).keySet();
	}
	
	private static void putBranches(Type t, SpatialType st, Segment first, Segment...segments) {
		branches.put(t, st, EnumSet.of(first, segments));
	}
}
