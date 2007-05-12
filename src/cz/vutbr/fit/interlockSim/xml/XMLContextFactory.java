/* Brno University of Technology
 * Faculty of Information Technology
 * 
 * BSc Thesis	2006/2007
 * 
 * Railway Interlocking Simulator
 * 
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.xml;

import java.awt.Point;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Iterator;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.transform.Source;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import cz.vutbr.fit.interlockSim.MyResourceBoundle;
import cz.vutbr.fit.interlockSim.context.Context;
import cz.vutbr.fit.interlockSim.context.ContextCreationException;
import cz.vutbr.fit.interlockSim.context.DefaultContext;
import cz.vutbr.fit.interlockSim.context.DefaultRailWayNetGrid;
import cz.vutbr.fit.interlockSim.context.EditingContext;
import cz.vutbr.fit.interlockSim.context.EditingContextFactory;
import cz.vutbr.fit.interlockSim.context.RailwayNetGrid;
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory;
import cz.vutbr.fit.interlockSim.objects.cells.Cell;
import cz.vutbr.fit.interlockSim.objects.cells.InOut;
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell;
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore;
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch;
import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment;
import cz.vutbr.fit.interlockSim.objects.cells.Cell.SpatialType;
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch.Type;
import cz.vutbr.fit.interlockSim.objects.paths.OrientedPathSeparator;
import cz.vutbr.fit.interlockSim.objects.paths.PathElement;
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock;
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock;
import cz.vutbr.fit.interlockSim.util.Doubleton;
import cz.vutbr.fit.interlockSim.util.Util;

/**
 * XML implemetation of {@link EditingContextFactory}
 *
 */
public class XMLContextFactory implements EditingContextFactory, SimulationContextFactory {
    //	EXTENSION co kdyz je delka koleje mezi InOuty mensi nez delka vlaku

	private class XMLContext extends DefaultContext {
		XMLContext(int cols, int rows) {
			super(cols, rows);
		}
	}
	
	private class Handler extends DefaultHandler {
		private XMLContext context;
		private boolean ended = false;
		private final Class<? extends PathElement> classes[] = (Class<? extends PathElement> []) new Class[] {
				RailSemaphore.class,
				RailSwitch.class,
				InOut.class,
				SimpleTrackBlock.class};
		
		@Override
		public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
			if (localName.equals(ROOT_ELEMENT_NAME)) {
				final int cols = getInt(uri, attributes, X);
				final int rows = getInt(uri, attributes, Y);
				context = new XMLContext(cols, rows);
				return;
			}
			
			if (context == null) throw new SAXException();
			final Class<? extends PathElement> clazz = classification(localName);
			
			if (NodeCell.class.isAssignableFrom(clazz)){
				final SpatialType spatialType = getEnum(uri, attributes, SpatialType.class);
				
				final Object[] args;
				if (OrientedPathSeparator.class.isAssignableFrom(clazz)) {
					final boolean orientation = getBoolean(uri, attributes, ATR_ORIENT_NAME);
					if (clazz == InOut.class) {
						final String name = attributes.getValue(uri, NAME);
						args = new Object[]{name, orientation, spatialType};
					} else {
						assert clazz == RailSemaphore.class : clazz; //zatim
						args = new Object[]{orientation, spatialType};
					}
				} else {
					assert clazz == RailSwitch.class : clazz; //zatim
					final Type type = getEnum(uri, attributes, RailSwitch.Type.class);
					args = new Object[]{spatialType, type};
				}
				
				final Point key = getPoint(uri, attributes, null);
				
				try {
					NodeCell node = (NodeCell) createNew(context, clazz, args);
					context.putCell(key, node);
				} catch (Exception e) {
					throw new SAXException(e);
				}
			} else if (TrackBlock.class.isAssignableFrom(clazz)) {
				assert clazz == SimpleTrackBlock.class : clazz; // zatim
				//hrana obecne:
				final Point from = getPoint(uri, attributes, FROM);
				final Point to = getPoint(uri, attributes, TO);
				assert !from.equals(to);
				
				final RailwayNetGrid railWayNetGrid = context.getRailWayNetGrid();
				final NodeCell fromNode = Util.assertNodeCell(railWayNetGrid.get(from));
				final NodeCell toNode = Util.assertNodeCell(railWayNetGrid.get(to));
				
				final Segment segmentFrom = getEnum(uri, attributes, Segment.class, FROM); assert segmentFrom != null;
				final Segment segmentTo = getEnum(uri, attributes, Segment.class, TO); assert segmentTo != null;
				
				//specialne: 
				final double maxSpeed1 = getDouble(uri, attributes, ATR_MAX_SPEED+FROM);
				final double maxSpeed2 = getDouble(uri, attributes, ATR_MAX_SPEED+TO);
				final double length = getDouble(uri, attributes, ATR_LENGTH);
				final SimpleTrackBlock trackBlock = new SimpleTrackBlock(fromNode, toNode, length, maxSpeed1, maxSpeed2);
				
				//a opet obecne:
				final Object result = context.hardJoin(segmentFrom, segmentTo, from, to, trackBlock);
				if (result == null) throw new SAXException("track block were not inserted");
			} else {
				assert false : clazz;
			}
				
		}
		
		private Class<? extends PathElement> classification(String localName) throws SAXException {
			for (Class<? extends PathElement> c : classes) {
				if (localName.equals(classToString(c))) {
					return c;
				}
			}
			throw new SAXException("Wrong tag name " + localName);
		}

		private <E extends Enum<E>> E getEnum(String uri, Attributes attributes, Class<E> enumClass) {
			return getEnum(uri, attributes, enumClass, "");
		}
		
		private <E extends Enum<E>> E getEnum(String uri, Attributes attributes, Class<E> enumClass, String name) {
			return Enum.valueOf(enumClass, attributes.getValue(uri, name+classToString(enumClass)));
		}

		private boolean getBoolean(String uri, Attributes attributes, String name) {
			return Boolean.parseBoolean(attributes.getValue(uri, name));
		}
		
		private int getInt(String uri, Attributes attributes, String name) throws SAXException {
			assert attributes != null && name != null;
			try {
				final int i = Integer.parseInt((attributes.getValue(uri, name)));
				return i;
			} catch (NumberFormatException e) {
				throw new SAXException("Wrong parameter " + name, e);
			}
		}
		
		private double getDouble(String uri, Attributes attributes, String name) throws SAXException {
			assert attributes != null && name != null;
			try {
				final double d = Double.parseDouble((attributes.getValue(uri, name)));
				return d;
			} catch (NumberFormatException e) {
				throw new SAXException("Wrong parameter " + name, e);
			}
		}
		
		private Point getPoint(String uri, Attributes attributes, String name) throws SAXException {
			final int x = getInt(uri, attributes, name==null ? X : name+X);
			final int y = getInt(uri, attributes, name==null ? Y : name+Y);
			return new Point(x,y);
		}
		
		@Override
		public void endElement(String uri, String localName, String qName) throws SAXException {
			// EMPTY
		}
		
		@Override
		public void endDocument() throws SAXException {
			ended = true;
		}
		
		DefaultContext getContext() {
			return ended ? context : null;
		}
	}
	
	private static final String ROOT_ELEMENT_NAME = "net";
	private static final String ATR_ORIENT_NAME = "orientation";
	private static final String ATR_LENGTH = "length";
	private static final String ATR_MAX_SPEED = "maxSpeed";
	
	private static final String X = "X";
	private static final String Y = "Y";
	private static final String FROM = "from";
	private static final String TO = "to";
	private static final String NAME = "name";
	
	private static final int DEFAULT_GRID_SIZE = 100;
	
	private static final XMLContextFactory instance = new XMLContextFactory();
	private Validator validator;

	private XMLContextFactory() {
		SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
	    final InputStream schemaStream = MyResourceBoundle.getInstance().getSchema();
	    assert schemaStream != null;
		Source schemaFile = new StreamSource(schemaStream);
	    
	    try {
			Schema schema = factory.newSchema(schemaFile);
			validator = schema.newValidator();
		} catch (SAXException e) {
			e.printStackTrace();
		} catch (Exception e) {
			assert false : e;
		}
	}
	
	/**
	 * @return Get factory for creating editing and simulation context from XML
	 */
	public static XMLContextFactory getInstance() {
		assert instance != null;
		return instance;
	}
	
	public DefaultContext createEmptyContext() {
		return new XMLContext(DEFAULT_GRID_SIZE, DEFAULT_GRID_SIZE);
	}

	public DefaultContext createContext(File file) throws ContextCreationException {
		try {
			return createContext(new FileReader(file));
		} catch (FileNotFoundException e) {
			throw new ContextCreationException(e);
		}
	}
	
	private DefaultContext createContext(Reader reader) throws ContextCreationException {
		assert validator != null;
		try {
			final InputSource inputSource = new InputSource(reader);
			final Handler handler = new Handler();
			validator.validate(new SAXSource(inputSource), new SAXResult(handler));
			return handler.getContext();
		} catch (Exception e) {
			throw new ContextCreationException(e);
		}
	}
	
	public DefaultContext createContext(InputStream stream) throws ContextCreationException {
		return createContext(new InputStreamReader(stream));
	}

	public boolean saveContext(Context context, OutputStream stream) {
		// TODO Auto-generated method stub
		return false;
	}
	
	private StringBuilder appendAtribute(final StringBuilder builder, String name, int value) {
		return appendAtribute(builder, name, Integer.toString(value));
	}
	
	private StringBuilder appendAtribute(final StringBuilder builder, String name, double value) {
		return appendAtribute(builder, name, Double.toString(value));
	}
	
//	private StringBuilder appendAtribute(final StringBuilder builder, String name, Object value) {
//		assert builder != null && name != null;
//		appendAtribute(builder, name, String.valueOf(value));
//		return builder;
//	}
	
	private StringBuilder appendAtribute(final StringBuilder builder, Enum<?> value) {
		assert value != null;
		final Class<?> clazz = value.getClass();
		return appendAtribute(builder, classToString(clazz), String.valueOf(value));
	}
	
	private StringBuilder appendAtribute(final StringBuilder builder, String name, Enum<?> value) {
		assert builder != null && name != null;
		builder.append(name);
		return appendAtribute(builder, value);
	}
	
	private StringBuilder appendAtribute(final StringBuilder builder, String name, String value) {
		assert builder != null && name != null && value != null;
		return builder.append(name).append("=\"").append(value).append('\"').append(' ');
	}
	
	private StringBuilder appendAtribute(final StringBuilder builder, String name, Point value) {
		assert value != null;
		builder.append(name);
		appendAtribute(builder, X, value.x);
		builder.append(name);
		return appendAtribute(builder, Y, value.y);
	}
	
	public boolean saveContext(Context context, File file) {
		final DefaultContext xmlContext = Util.assertInstanceOf(DefaultContext.class, context);//zatim
		final DefaultRailWayNetGrid railWayNetGrid = xmlContext.getRailWayNetGrid();
		try {
		    final FileWriter fileWriter = new FileWriter(file);
		    final StringBuilder stringBuilder = new StringBuilder("<?xml version=\"1.0\"?>\n<!DOCTYPE ");
		    stringBuilder.append(ROOT_ELEMENT_NAME).append(">\n");
		    stringBuilder.append('<').append(ROOT_ELEMENT_NAME).append(' ');
		    appendAtribute(stringBuilder, X, railWayNetGrid.getCols());
		    appendAtribute(stringBuilder, Y, railWayNetGrid.getRows());
		    stringBuilder.append(">\n");
		    fileWriter.write(stringBuilder.toString());
		    
		    for (Point p : xmlContext.getGraph().nodeSet()) {
		    	final Cell cell = railWayNetGrid.get(p);
				StringBuilder builder = tagFor(p, cell);
				spacing(builder, 1);
				fileWriter.write(builder.toString());
		    }
		    
		    for (Map.Entry<Doubleton<Point, Segment>, TrackBlock> i : xmlContext.getGraph().entrySet()) {
		    	final Iterator<Point> iterator = i.getKey().iterator();
		    	assert iterator.hasNext();
		    	Point p1 = iterator.next();
		    	assert iterator.hasNext();
		    	Point p2 = iterator.next();
		    	
				StringBuilder builder = tagFor(p1, p2, i.getKey(), i.getValue());
				spacing(builder, 1);
				fileWriter.write(builder.toString());
		    }
		    
		    fileWriter.write("</" + ROOT_ELEMENT_NAME + ">\n");
		    fileWriter.close();
		} catch (IOException e) {
		    assert false : e; 
		}
		return false;
	}

	//jednotici metoda...
	private String classToString(Class<?> clazz) {
		return clazz.getSimpleName();
	}
	
	//odsazeni
	private void spacing(StringBuilder builder, int count) {
		assert count > 0;
		for (int i = 0; i < count;i++) builder.insert(0, '\t');
	}
	
	private StringBuilder tagFor(final Point p1, final Point p2, final Doubleton<Point, Segment> key, final TrackBlock value) {
		final StringBuilder builder = new StringBuilder();
		final Class<? extends TrackBlock> clazz = value.getClass();
		beginOfTag(builder, clazz);
		appendAtribute(builder, FROM, p1);
		appendAtribute(builder, TO, p2);
		appendAtribute(builder, FROM, key.getValue(p1));
		appendAtribute(builder, TO, key.getValue(p2));
		appendAtribute(builder, ATR_LENGTH, value.length());
		appendAtribute(builder, ATR_MAX_SPEED+FROM, value.maxSpeed(value.ends()[0]));
		appendAtribute(builder, ATR_MAX_SPEED+TO, value.maxSpeed(value.ends()[1]));
		closingEndOfTag(builder);
		return builder;
	}

	private StringBuilder tagFor(final Point key, final Cell cell) {
		final StringBuilder builder = new StringBuilder();
		final Class<? extends Cell> clazz = cell.getClass();
		beginOfTag(builder, clazz);
		appendAtribute(builder, "", key);
		appendAtribute(builder, cell.getSpatialType());
		if (cell instanceof OrientedPathSeparator) {
			appendAtribute(builder, ATR_ORIENT_NAME, 
					Boolean.toString(((OrientedPathSeparator) cell).getOrientation()));
		}
		if (clazz == RailSwitch.class) {
			appendAtribute(builder, ((RailSwitch) cell).getType());
		} else if (clazz == InOut.class) {
			appendAtribute(builder, NAME, ((InOut) cell).getName());
		}
		closingEndOfTag(builder);
		return builder;
	}

	private StringBuilder closingEndOfTag(final StringBuilder builder) {
		return builder.append("/>\n");
	}

	private StringBuilder beginOfTag(final StringBuilder builder, final Class<?> clazz) {
		return builder.append('<').append(classToString(clazz)).append(' ');
	}

	public DefaultContext createContext(EditingContext editingContext) {
		return Util.assertInstanceOf(DefaultContext.class, editingContext);//zatim
	}

	public Object createNew(EditingContext context, Class<?> clazz, Object... arguments) throws Exception {
		assert context !=null && clazz != null;
		final Class<?>[] toClass = Util.toClass(arguments);
		final Constructor<?> constructor = clazz.getConstructor(toClass);
		
		Object newInstance;
		try {
			newInstance = constructor.newInstance(arguments);
		} catch (InvocationTargetException e) {
			throw Util.assertInstanceOf(Exception.class, e.getTargetException());
		}
		return newInstance;
	}

}
