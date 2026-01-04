/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.util;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

/**
 * Debug utility
 */
public class Report {
	public static boolean on = true;
	final static String firstPrefix = "- ";
	public static String itemSeparator = " ";
	public static String reportSeparator = "";
	public static PrintStream out = System.out;
	private static final int begin = 4;

	private final static StringBuffer buf = new StringBuffer(200);

	private static void output(String prefix, StackTraceElement element, Object... o) {
		assert buf != null;
		//buf.append(prefix).append(element);
		if (o!=null && o.length>0) {
			//do {
			//    buf.append(' ');
			//} while (buf.length()<80);
			for (int i=0; ; ++i) {
				final String s = String.valueOf(o[i]).replaceAll("\n", "\n    ");//.replaceAll("\r", "\\r").replaceAll("\t", "\\t");
				buf.append(s);
				if (i==o.length-1) break;
				buf.append(itemSeparator);
			}
		}
		buf.append("\n");
	}
	private static void output(int count, Object... o) {
		if (!on) return;
		final StackTraceElement[] elements = getElements();
		count = getCount(count, elements);
		for (int i=0; i<count; ++i) {
			String prefix = i==0 ? firstPrefix : "  ";
			Object[] a = i==0 ? o : null;
//            Object oi = i<o.length ? o[i] : "";
			output(prefix, elements[begin+i], a);
		}
		flush();
	}

	private static void oneOutput(int which, Object... o) {
		if (!on) return;
		final StackTraceElement[] elements = getElements();
		getCount(which+1, elements);
		output(firstPrefix, elements[begin+which], o);
		flush();
	}

	private static int getCount(int count, final StackTraceElement[] elements) {
		count = Math.min(count, elements.length-begin);
		assert count > 0;
		return count;
	}

	private static StackTraceElement[] getElements() {
		final StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
		assert stackTrace != null;
		return stackTrace;
	}

	private static void flush() {
		buf.append(reportSeparator);
		out.print(buf);
		out.flush();
		buf.delete(0, buf.length());
	}

	/**
	 * Report the source line number of the caller (as takem from the stack trace) and the supplied values o.
	 * @param o the values to be reported
	 */
	public static void a(Object... o) {
		output(1, o);
	}
	public static void a2(Object... o) {
		output(2, o);
	}

	public static void a3(Object... o) {
		output(3, o);
	}
	/**
	 * Report the first n elements of the stack trace and the supplied values o.
	 * @param n number of output lines
	 * @param o the values to be reported
	 */
	public static void n(int n, Object... o) {
		output(n, o);
	}

	public static void e(Throwable e) {
		if (!on) return;
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		final PrintStream ps = new PrintStream(out);
		while (true) {
			e.printStackTrace(ps);
			e = e.getCause();
			if (e==null) break;
			ps.println("CAUSED BY");
		}
		output(1, out.toString());
	}

}
