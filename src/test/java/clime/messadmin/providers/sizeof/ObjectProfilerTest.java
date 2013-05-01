/**
 * 
 */
package clime.messadmin.providers.sizeof;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionContext;

import clime.messadmin.model.Application;
import clime.messadmin.model.ApplicationInfo;
import clime.messadmin.model.Request;
import clime.messadmin.model.RequestInfo;
import clime.messadmin.model.Server;
import clime.messadmin.model.ServerInfo;
import clime.messadmin.model.Session;
import clime.messadmin.model.SessionInfo;
import clime.messadmin.model.stats.HitsCounter;
import clime.messadmin.model.stats.MinMaxTracker;
import clime.messadmin.model.stats.StatisticsAgregator;

import junit.framework.TestCase;

/**
 * @author C&eacute;drik LIME
 */
public class ObjectProfilerTest extends TestCase {
	private static HttpSession emptySession = new HttpSession() {
		public boolean isNew() {return false;}
		public void invalidate() {}
		public void removeValue(String name) {}
		public void removeAttribute(String name) {}
		public void putValue(String name, Object value) {}
		public void setAttribute(String name, Object value) {}
		public String[] getValueNames() {return null;}
		public Enumeration getAttributeNames() {return null;}
		public Object getValue(String name) {return null;}
		public Object getAttribute(String name) {return null;}
		public HttpSessionContext getSessionContext() {return null;}
		public int getMaxInactiveInterval() {return 0;}
		public void setMaxInactiveInterval(int interval) {}
		public ServletContext getServletContext() {return null;}
		public long getLastAccessedTime() {return 0;}
		public String getId() {
			return "0123456789ABCDEF";
		}
		public long getCreationTime() {return 0;}
	};
	protected String[] sunProblematicClassesNames;
	protected Class[] sunProblematicClasses;

	/**
	 * Constructor for ObjectProfilerTest.
	 * @param name
	 */
	public ObjectProfilerTest(String name) {
		super(name);
		sunProblematicClassesNames = new String[] {
				"java.lang.Throwable", // 1.3+	20
				"sun.reflect.ConstantPool", // 1.5+	8
				"sun.reflect.UnsafeStaticFieldAccessorImpl", // 1.4+
				"sun.reflect.UnsafeStaticBooleanFieldAccessorImpl", // 1.4+
				"sun.reflect.UnsafeStaticByteFieldAccessorImpl", // 1.4+
				"sun.reflect.UnsafeStaticShortFieldAccessorImpl", // 1.4+
				"sun.reflect.UnsafeStaticIntegerFieldAccessorImpl", // 1.4+
				"sun.reflect.UnsafeStaticLongFieldAccessorImpl", // 1.4+
				"sun.reflect.UnsafeStaticCharacterFieldAccessorImpl", // 1.4+
				"sun.reflect.UnsafeStaticFloatFieldAccessorImpl", // 1.4+
				"sun.reflect.UnsafeStaticDoubleFieldAccessorImpl", // 1.4+
				"sun.reflect.UnsafeStaticObjectFieldAccessorImpl" // 1.4+
			};
		List classes = new ArrayList(sunProblematicClassesNames.length);
		for (int i = 0; i < sunProblematicClassesNames.length; ++i) {
			String className = sunProblematicClassesNames[i];
			try {
				classes.add(Class.forName(className));
			} catch (ClassNotFoundException cnfe) {
				//System.out.println(cnfe);
			}
		}
		sunProblematicClasses = (Class[]) classes.toArray(new Class[0]);
	}

	public static void main(String[] args) {
		junit.textui.TestRunner.run(ObjectProfilerTest.class);
	}

	/**
	 * {@inheritDoc}
	 */
	protected void setUp() throws Exception {
		super.setUp();
	}

	/**
	 * {@inheritDoc}
	 */
	protected void tearDown() throws Exception {
		super.tearDown();
	}

	/*
	 * Test method for 'clime.messadmin.utils.ObjectProfiler.sizeof(Object)'
	 */
	public void testSizeof() {
		assertEquals(0, ObjectProfiler.sizeof(null));
		assertEquals(0, ObjectProfiler.sizeof(Object.class));
		assertEquals(0, ObjectProfiler.sizeof(Boolean.TRUE));
		assertEquals(0, ObjectProfiler.sizeof(Locale.FRENCH));
		assertEquals(0, ObjectProfiler.sizeof(Collections.EMPTY_LIST));
		assertEquals(0, ObjectProfiler.sizeof(BigInteger.ONE));
		assertEquals(8, ObjectProfiler.sizeof(new Object()));
		assertEquals(20, ObjectProfiler.sizeof(new Date()));
		assertEquals(40, ObjectProfiler.sizeof(new String()));
		assertEquals(16, ObjectProfiler.sizeof(new Object[0]));
		assertEquals(16, ObjectProfiler.sizeof(new String[0]));
		assertEquals(56, ObjectProfiler.sizeof(new Object[10]));
		assertEquals(56, ObjectProfiler.sizeof(new String[10]));

		System.out.println("sizeOf(HitsCounter) == " + ObjectProfiler.sizeof(new HitsCounter()));
		System.out.println("sizeOf(MinMaxTracker) == " + ObjectProfiler.sizeof(new MinMaxTracker()));
		System.out.println("sizeOf(StatisticsAgregator) == " + ObjectProfiler.sizeof(new StatisticsAgregator()));
		System.out.println("sizeOf(Request) == " + ObjectProfiler.sizeof(new Request(null)));
		System.out.println("sizeOf(empty RequestInfo) == " + ObjectProfiler.sizeof(new RequestInfo(null)));
		System.out.println("sizeOf(Session) == " + (ObjectProfiler.sizeof(new Session(emptySession))-ObjectProfiler.sizeof(emptySession)));
		System.out.println("sizeOf(empty SessionInfo) == " + ObjectProfiler.sizeof(new SessionInfo()));
		System.out.println("sizeOf(Application) == " + ObjectProfiler.sizeof(new Application(null)));
		System.out.println("sizeOf(empty ApplicationInfo) == " + ObjectProfiler.sizeof(new ApplicationInfo(null)));
		System.out.println("sizeOf(Server) == " + ObjectProfiler.sizeof(Server.getInstance()));
		System.out.println("sizeOf(empty ServerInfo) == " + ObjectProfiler.sizeof(new ServerInfo()));
	}

	public void testSunBuggyJVM() {
		for (int i = 0; i < sunProblematicClasses.length; ++i) {
			Class clazz = sunProblematicClasses[i];
			Object instance = null;
			try {
				instance = clazz.newInstance();
			} catch (InstantiationException e) {
				//System.err.println(e);
			} catch (IllegalAccessException e) {
				//System.err.println(e);
			}
			if (instance != null) {
				ObjectProfiler.sizeof(instance); //should not throw exception
			}
		}
	}
}
