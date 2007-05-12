/* Brno University of Technology
 * Faculty of Information Technology
 * 
 * BSc Thesis	2006/2007
 * 
 * Railway Interlocking Simulator
 * 
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * very simple ADT Multimap prototype
 *
 * @param <K> key
 * @param <V> value
 */
public class TreeMultiMap<K,V> {
    private final SortedMap<K,Set<V>> map = new TreeMap<K,Set<V>>();
    
    /**
     * put element to multimap
     * @param key
     * @param value
     */
    public void put(K key, V value) {
	Set<V> valueSet = map.get(key);
	if (valueSet == null) {
	    valueSet = new LinkedHashSet<V>();
	    map.put(key, valueSet);
	}
	valueSet.add(value);
    }
    
    /**
     * get elements from multimap
     * @param key
     * @return set of elements
     */
    public Set<V> get(K key) {
    	//	EXTENSION jak to ma byt spravne...
    	return Collections.unmodifiableSet(map.get(key));
    }
    
    @Override
    public String toString() {
	return map.toString();
    }

    /**
     * Values in map - reading access to multimap
     * @return values
     */
    public Collection<V> values() {
    	//	EXTENSION jak to ma byt spravne...
    	Collection<V> coll = new ArrayList<V>();
    	for (Set<V> set : map.values()) {
    		coll.addAll(set);
    	}
		return coll;
    }
}
