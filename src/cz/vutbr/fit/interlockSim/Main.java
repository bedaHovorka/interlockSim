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
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;

import cz.vutbr.fit.interlockSim.context.Context;
import cz.vutbr.fit.interlockSim.context.ContextCreationException;
import cz.vutbr.fit.interlockSim.context.ContextFactory;
import cz.vutbr.fit.interlockSim.context.DefaultContext;
import cz.vutbr.fit.interlockSim.context.EditingContextFactory;
import cz.vutbr.fit.interlockSim.context.EmptyContextException;
import cz.vutbr.fit.interlockSim.context.SimulationContext;
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory;
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType;
import cz.vutbr.fit.interlockSim.gui.Frame;
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop;
import cz.vutbr.fit.interlockSim.sim.SimulationException;
import cz.vutbr.fit.interlockSim.util.Report;
import cz.vutbr.fit.interlockSim.util.Util;
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory;

/**
 * Main class, for run program
 *
 * usage: java -ea cz.vutbr.fit.interlockSim.Main (sim|edit) [file]
 *        java -ea cz.vutbr.fit.interlockSim.Main example name
 *
 * or: ant start
 *
 * !!! Please check JAVA_HOME for JDK/JRE 1.6 !!!
 */
public class Main {
	/**
	 * Program name
	 */
	public static final String PROGRAM_NAME = "InterlockSim";
	/**
	 * Version
	 */
	public static final String PROGRAM_VERSION = "0.1-bachelor";
	/**
	 * Program title
	 */
	public static final String PROGRAM_FULL_NAME = PROGRAM_NAME + " " + PROGRAM_VERSION;

	private Frame frame;
	private static Main instance = new Main();

	//kam poustet hlasky
	private static final PrintStream out = System.err;

	private Main() {
		//?
	}

	private void loadGui(String[] args) {
		frame = new Frame();
		frame.setContext(createContext(args));
		frame.setVisible(true);
	}

	private final Context createContext(String[] args) {
		final EditingContextFactory factory = (EditingContextFactory) getContextFactory();

		if (args.length > 1) {
			try {
				return factory.createContext(new File(args[1]));
			} catch (ContextCreationException e) {
				e.printStackTrace();
				System.exit(1);
			}
		}
		return factory.createEmptyContext();
	}

	private void loadSim(String[] args) {
		final SimulationContext context = (SimulationContext) createContext(args);
		context.addReportTypes(ReportType.values());
		try {
			context.run();
		} catch (EmptyContextException e) {
			out.println("You dont specify valid file");
		} catch (SimulationException e) {
			out.println("Simulation failed");
			e.printStackTrace();
		}
	}

	private void runExample(String[] args) {
		if (args.length == 1) {
			printListOfExamples();
			return;
		}
		final String name = args[1];
		try {
			final Method method = Main.class.getMethod(name, SimulationContextFactory.class, String[].class);
			if (!method.isAnnotationPresent(Example.class)) throw new NoSuchMethodException("Method " + name + " isn't annotated");
			final XMLContextFactory factory = (XMLContextFactory) getContextFactory();
			SimulationContext context = (SimulationContext) method.invoke(this, factory, args);
			context.run();
		} catch (NoSuchMethodException e) {
			out.println("Example with name "+ name + " not exist");
			printListOfExamples();
		} catch (SimulationException e) {
			out.println("Example simulation failed");
		} catch (EmptyContextException e) {
			out.println("Example simulation could not started - empty context");
		} catch (InvocationTargetException e) {
			final Throwable cause = e.getCause();
			if (cause instanceof ContextCreationException) {
				out.println(cause.getMessage());
				return;
			} else if (cause instanceof NumberFormatException) {
				out.println(cause.getMessage().concat(" cannot convert to number"));
				return;
			}
			Report.e(cause);
		} catch (Exception e) {
			out.println("Example inilialization failed");
			Report.e(e);
		}
	}

	/**
	 * @param factory
	 * @return loaded and initialed context
	 * @throws ContextCreationException
	 */
	@Example
	public SimulationContext shuntingLoop(SimulationContextFactory factory, String[] args) throws ContextCreationException {
		if (args.length < 3) {
			throw new ContextCreationException("End time of simulation not inserted");
		}
		final InputStream stream = MyResourceBoundle.getInstance().getFile("vyhybna.xml");
		final DefaultContext context = Util.assertInstanceOf(DefaultContext.class, factory.createContext(stream));
		final long time = Long.parseLong(args[2]);
		context.setMainProcess(new ShuntingLoop(context, time));
		return context;
	}

	private void printListOfExamples() {
		final ArrayList<String> list = new ArrayList<String>();
		for (Method m : Main.class.getDeclaredMethods()) {
			if (Modifier.isPublic(m.getModifiers()) && m.isAnnotationPresent(Example.class)) {
				list.add(m.getName());
			}
		}
		Collections.sort(list, String.CASE_INSENSITIVE_ORDER);
		out.println(list.size() > 0 ? "You must specifify valid name of example\nList of examles: "+ list :
						"No Examples in program");
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		if (isArgs(args, "sim")) {
			instance.loadSim(args);
		} else if (isArgs(args, "example")) {
			instance.runExample(args);
		} else if (isArgs(args, "edit")) {
			instance.loadGui(args);
		} else {
			out.println("usage: <java> cz.vutbr.fit.interlockSim.Main (sim|edit) [file]\n" +
								"\t\t example [name]"
							  );
		}
	}

	private static final boolean isArgs(String[] args, String firstArg) {
		return args.length > 0 && args[0].equals(firstArg);
	}

	/**
	 * @return Main singleton instance
	 */
	public static Main getInstance() {//CAUTION pouzivat jen v nejnutnejsich pripadech
		assert instance != null;
		return instance;
	}

	/**
	 * temporary method
	 * @return current Context Factory
	 */
	public ContextFactory getContextFactory() {
		return XMLContextFactory.getInstance();
	}
}
