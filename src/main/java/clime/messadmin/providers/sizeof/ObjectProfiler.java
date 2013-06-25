//package com.vladium.utils;
package clime.messadmin.providers.sizeof;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryNotificationInfo;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.jar.Pack200;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRelation;
import javax.accessibility.AccessibleRole;
import javax.accessibility.AccessibleState;
import javax.management.monitor.MonitorNotification;
import javax.management.relation.RelationNotification;
import javax.management.remote.JMXConnectionNotification;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.rmi.RMIConnectorServer;
import javax.naming.Context;
import javax.print.DocFlavor;
import javax.security.sasl.Sasl;
import javax.xml.XMLConstants;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.transform.OutputKeys;

// ----------------------------------------------------------------------------
/**
 * This non-instantiable class presents an API for object sizing
 * as described in the
 * <a href="http://www.javaworld.com/javaqa/2003-12/02-qa-1226-sizeof_p.html">article</a>.
 * See individual methods for details.
 *
 * <P>
 * This implementation is 32 bits J2SE 1.4+ only. You would need to code your own
 * identity hashmap to port this to earlier Java versions.
 *
 * <P>
 * Security: this implementation uses AccessController.doPrivileged() so it
 * could be granted privileges to access non-public class fields separately from
 * your main application code. The minimum set of permissions necessary for this
 * class to function correctly follows:
 *
 * <pre>
 *	   permission java.lang.RuntimePermission &quot;accessDeclaredMembers&quot;;
 *	   permission java.lang.reflect.ReflectPermission &quot;suppressAccessChecks&quot;;
 * </pre>
 *
 * @author (C) <a href="http://www.javaworld.com/columns/jw-qna-index.shtml">Vlad
 *		 Roubtsov</a>, 2003
 */
public abstract class ObjectProfiler {
	// public: ................................................................

	// the following constants are physical sizes (in bytes) and are JVM-dependent:
	// [the current values are Ok for most 32-bit JVMs]

	public static final int OBJECT_SHELL_SIZE  = 8; // java.lang.Object shell
													// size in bytes
	public static final int OBJREF_SIZE        = 4;
	public static final int LONG_FIELD_SIZE    = 8;
	public static final int INT_FIELD_SIZE     = 4;
	public static final int SHORT_FIELD_SIZE   = 2;
	public static final int CHAR_FIELD_SIZE    = 2;
	public static final int BYTE_FIELD_SIZE    = 1;
	public static final int BOOLEAN_FIELD_SIZE = 1;
	public static final int DOUBLE_FIELD_SIZE  = 8;
	public static final int FLOAT_FIELD_SIZE   = 4;

	/**
	 * Estimates the full size of the object graph rooted at 'obj'. Duplicate
	 * data instances are correctly accounted for. The implementation is not
	 * recursive.
	 *
	 * @param obj
	 *			input object instance to be measured
	 * @return 'obj' size [0 if 'obj' is null']
	 */
	public static long sizeof(final Object obj) {
		if (null == obj || isSharedFlyweight(obj)) {
			return 0;
		}

		final IdentityHashMap visited = new IdentityHashMap(80000);

		try {
			return computeSizeof(obj, visited, CLASS_METADATA_CACHE);
		} catch (RuntimeException re) {
			//re.printStackTrace();//DEBUG
			return -1;
		} catch (NoClassDefFoundError ncdfe) {
			// BUG: throws "java.lang.NoClassDefFoundError: org.eclipse.core.resources.IWorkspaceRoot" when run in WSAD 5
			// see http://www.javaworld.com/javaforums/showflat.php?Cat=&Board=958763&Number=15235&page=0&view=collapsed&sb=5&o=
			//System.err.println(ncdfe);//DEBUG
			return -1;
		}
	}

	/**
	 * Estimates the full size of the object graph rooted at 'obj' by
	 * pre-populating the "visited" set with the object graph rooted at 'base'.
	 * The net effect is to compute the size of 'obj' by summing over all
	 * instance data contained in 'obj' but not in 'base'.
	 *
	 * @param base
	 *			graph boundary [may not be null]
	 * @param obj
	 *			input object instance to be measured
	 * @return 'obj' size [0 if 'obj' is null']
	 */
	public static long sizedelta(final Object base, final Object obj) {
		if (null == obj || isSharedFlyweight(obj)) {
			return 0;
		}
		if (null == base) {
			throw new IllegalArgumentException("null input: base");
		}

		final IdentityHashMap visited = new IdentityHashMap(40000);

		try {
			computeSizeof(base, visited, CLASS_METADATA_CACHE);
			return visited.containsKey(obj) ? 0 : computeSizeof(obj, visited, CLASS_METADATA_CACHE);
		} catch (RuntimeException re) {
			return -1;
		} catch (NoClassDefFoundError ncdfe) {
			// BUG: throws "java.lang.NoClassDefFoundError: org.eclipse.core.resources.IWorkspaceRoot" when run in WSAD 5
			// see http://www.javaworld.com/javaforums/showflat.php?Cat=&Board=958763&Number=15235&page=0&view=collapsed&sb=5&o=
			return -1;
		}
	}

	// protected: .............................................................

	// package: ...............................................................

	// private: ...............................................................

	/*
	 * Internal class used to cache class metadata information.
	 */
	private static final class ClassMetadata {
		ClassMetadata(final int primitiveFieldCount, final int shellSize,
				final Field[] refFields) {
			m_primitiveFieldCount = primitiveFieldCount;
			m_shellSize = shellSize;
			m_refFields = refFields;
		}

		// all fields are inclusive of superclasses:

		final int m_primitiveFieldCount;

		final int m_shellSize; // class shell size

		final Field[] m_refFields; // cached non-static fields (made accessible)

	} // end of nested class

	private static final class ClassAccessPrivilegedAction implements PrivilegedExceptionAction<Field[]> {
		/** {@inheritDoc} */
		public Field[] run() throws Exception {
			return m_cls.getDeclaredFields();
		}

		void setContext(final Class cls) {
			m_cls = cls;
		}

		private Class m_cls;

	} // end of nested class

	private static final class FieldAccessPrivilegedAction implements PrivilegedExceptionAction {
		/** {@inheritDoc} */
		public Object run() throws Exception {
			m_field.setAccessible(true);
			return null;
		}

		void setContext(final Field field) {
			m_field = field;
		}

		private Field m_field;

	} // end of nested class

	private ObjectProfiler() {
	} // this class is not extendible

	/*
	 * The main worker method for sizeof() and sizedelta().
	 */
	private static long computeSizeof(Object obj, final IdentityHashMap visited,
			final Map<Class,ClassMetadata> metadataMap) {
		// this uses depth-first traversal; the exact graph traversal algorithm
		// does not matter for computing the total size and this method could be
		// easily adjusted to do breadth-first instead (addLast() instead of
		// addFirst()),
		// however, dfs/bfs require max queue length to be the length of the
		// longest
		// graph path/width of traversal front correspondingly, so I expect
		// dfs to use fewer resources than bfs for most Java objects;

		if (null == obj || isSharedFlyweight(obj)) {
			return 0;
		}

		final LinkedList queue = new LinkedList();

		visited.put(obj, obj);
		queue.add(obj);

		long result = 0;

		final ClassAccessPrivilegedAction caAction = new ClassAccessPrivilegedAction();
		final FieldAccessPrivilegedAction faAction = new FieldAccessPrivilegedAction();

		while (!queue.isEmpty()) {
			obj = queue.removeFirst();
			final Class objClass = obj.getClass();

			int skippedBytes = skipClassDueToSunJVMBug(objClass);
			if (skippedBytes > 0) {
				result += skippedBytes; // can't do better than that
				continue;
			}

			if (objClass.isArray()) {
				final int arrayLength = Array.getLength(obj);
				final Class componentType = objClass.getComponentType();

				result += sizeofArrayShell(arrayLength, componentType);

				if (!componentType.isPrimitive()) {
					// traverse each array slot:
					for (int i = 0; i < arrayLength; ++i) {
						final Object ref = Array.get(obj, i);

						if ((ref != null) && !visited.containsKey(ref)) {
							visited.put(ref, ref);
							queue.addFirst(ref);
						}
					}
				}
			} else { // the object is of a non-array type
				final ClassMetadata metadata = getClassMetadata(objClass,
						metadataMap, caAction, faAction);
				final Field[] fields = metadata.m_refFields;

				result += metadata.m_shellSize;

				// traverse all non-null ref fields:
				for (int f = 0, fLimit = fields.length; f < fLimit; ++f) {
					final Field field = fields[f];

					final Object ref;
					try { // to get the field value:
						ref = field.get(obj);
					} catch (Exception e) {
						throw new RuntimeException("cannot get field ["
								+ field.getName() + "] of class ["
								+ field.getDeclaringClass().getName() + "]: "
								+ e.toString());
					}

					if ((ref != null) && !visited.containsKey(ref)) {
						visited.put(ref, ref);
						queue.addFirst(ref);
					}
				}
			}
		}

		return result;
	}

	/*
	 * A helper method for manipulating a class metadata cache.
	 */
	private static ClassMetadata getClassMetadata(final Class cls,
			final Map<Class,ClassMetadata> metadataMap,
			final ClassAccessPrivilegedAction caAction,
			final FieldAccessPrivilegedAction faAction) {
		if (null == cls) {
			return null;
		}

		ClassMetadata result;
		synchronized (metadataMap) {
			result = metadataMap.get(cls);
		}
		if (result != null) {
			return result;
		}

		int primitiveFieldCount = 0;
		int shellSize = OBJECT_SHELL_SIZE; // java.lang.Object shell
		final List<Field> refFields = new LinkedList<Field>();

		final Field[] declaredFields;
		try {
			caAction.setContext(cls);
			declaredFields = AccessController.doPrivileged(caAction);
		} catch (PrivilegedActionException pae) {
			throw new RuntimeException(
					"could not access declared fields of class "
							+ cls.getName() + ": " + pae.getException());
		}

		for (int f = 0; f < declaredFields.length; ++f) {
			final Field field = declaredFields[f];
			if (Modifier.isStatic(field.getModifiers())) {
				continue;
			}
			/* Can't do that: HashMap data is transient, for example...
			if (Modifier.isTransient(field.getModifiers())) {
				shellSize += OBJREF_SIZE;
				continue;
			}
			*/

			final Class fieldType = field.getType();
			if (fieldType.isPrimitive()) {
				// memory alignment ignored:
				shellSize += sizeofPrimitiveType(fieldType);
				++primitiveFieldCount;
			} else {
				// prepare for graph traversal later:
				if (!field.isAccessible()) {
					try {
						faAction.setContext(field);
						AccessController.doPrivileged(faAction);
					} catch (PrivilegedActionException pae) {
						throw new RuntimeException("could not make field "
								+ field + " accessible: " + pae.getException());
					}
				}

				// memory alignment ignored:
				shellSize += OBJREF_SIZE;
				refFields.add(field);
			}
		}

		// recurse into superclass:
		final ClassMetadata superMetadata = getClassMetadata(cls
				.getSuperclass(), metadataMap, caAction, faAction);
		if (superMetadata != null) {
			primitiveFieldCount += superMetadata.m_primitiveFieldCount;
			shellSize += superMetadata.m_shellSize - OBJECT_SHELL_SIZE;
			refFields.addAll(Arrays.asList(superMetadata.m_refFields));
		}

		final Field[] _refFields = new Field[refFields.size()];
		refFields.toArray(_refFields);

		result = new ClassMetadata(primitiveFieldCount, shellSize, _refFields);
		synchronized (metadataMap) {
			metadataMap.put(cls, result);
		}

		return result;
	}

	/*
	 * Computes the "shallow" size of an array instance.
	 */
	private static int sizeofArrayShell(final int length, final Class componentType) {
		// this ignores memory alignment issues by design:

		final int slotSize = componentType.isPrimitive() ? sizeofPrimitiveType(componentType)
				: OBJREF_SIZE;

		return OBJECT_SHELL_SIZE + INT_FIELD_SIZE + OBJREF_SIZE + length * slotSize;
	}

	/*
	 * Returns the JVM-specific size of a primitive type.
	 */
	private static int sizeofPrimitiveType(final Class type) {
		if (type == int.class) {
			return INT_FIELD_SIZE;
		} else if (type == long.class) {
			return LONG_FIELD_SIZE;
		} else if (type == short.class) {
			return SHORT_FIELD_SIZE;
		} else if (type == byte.class) {
			return BYTE_FIELD_SIZE;
		} else if (type == boolean.class) {
			return BOOLEAN_FIELD_SIZE;
		} else if (type == char.class) {
			return CHAR_FIELD_SIZE;
		} else if (type == double.class) {
			return DOUBLE_FIELD_SIZE;
		} else if (type == float.class) {
			return FLOAT_FIELD_SIZE;
		} else {
			throw new IllegalArgumentException("not primitive: " + type);
		}
	}

	// class metadata cache:
	private static final Map<Class,ClassMetadata> CLASS_METADATA_CACHE = new WeakHashMap<Class,ClassMetadata>(101);

	static final Class[] sunProblematicClasses;
	static final Map<String, Integer> sunProblematicClassesSizes;

	static {
		Map<String, Integer> classesSizes = new HashMap<String, Integer>();
		classesSizes.put("java.lang.Class", Integer.valueOf(0));//not really a pb, but since this is shared, so there's no point in going further
		// 1.3+
		classesSizes.put("java.lang.Throwable", Integer.valueOf(OBJECT_SHELL_SIZE+4*OBJREF_SIZE));
		// 1.4+
		classesSizes.put("sun.reflect.UnsafeStaticFieldAccessorImpl",        Integer.valueOf(OBJECT_SHELL_SIZE));//unknown
		classesSizes.put("sun.reflect.UnsafeStaticBooleanFieldAccessorImpl", Integer.valueOf(OBJECT_SHELL_SIZE));//unknown
		classesSizes.put("sun.reflect.UnsafeStaticByteFieldAccessorImpl",    Integer.valueOf(OBJECT_SHELL_SIZE));//unknown
		classesSizes.put("sun.reflect.UnsafeStaticShortFieldAccessorImpl",   Integer.valueOf(OBJECT_SHELL_SIZE));//unknown
		classesSizes.put("sun.reflect.UnsafeStaticIntegerFieldAccessorImpl", Integer.valueOf(OBJECT_SHELL_SIZE));//unknown
		classesSizes.put("sun.reflect.UnsafeStaticLongFieldAccessorImpl",    Integer.valueOf(OBJECT_SHELL_SIZE));//unknown
		classesSizes.put("sun.reflect.UnsafeStaticCharacterFieldAccessorImpl", Integer.valueOf(OBJECT_SHELL_SIZE));//unknown
		classesSizes.put("sun.reflect.UnsafeStaticFloatFieldAccessorImpl",   Integer.valueOf(OBJECT_SHELL_SIZE));//unknown
		classesSizes.put("sun.reflect.UnsafeStaticDoubleFieldAccessorImpl",  Integer.valueOf(OBJECT_SHELL_SIZE));//unknown
		classesSizes.put("sun.reflect.UnsafeStaticObjectFieldAccessorImpl",  Integer.valueOf(OBJECT_SHELL_SIZE));//unknown
		// 1.5+
		classesSizes.put("java.lang.Enum", Integer.valueOf(0));//not really a pb, but since this is shared, so there's no point in going further
		classesSizes.put("sun.reflect.ConstantPool", Integer.valueOf(OBJECT_SHELL_SIZE + OBJECT_SHELL_SIZE));
		sunProblematicClassesSizes = Collections.unmodifiableMap(classesSizes);

		List classes = new ArrayList(sunProblematicClassesSizes.size());
		Iterator iter = sunProblematicClassesSizes.entrySet().iterator();
		while (iter.hasNext()) {
			Map.Entry entry = (Map.Entry) iter.next();
			String className = (String) entry.getKey();
			try {
				classes.add(Class.forName(className));
			} catch (ClassNotFoundException cnfe) {
			//} catch (ExceptionInInitializerError eiie) {
			//} catch (NoClassDefFoundError ncdfe) {
			//} catch (UnsatisfiedLinkError le) {
			} catch (LinkageError le) {
				// BEA JRockit 1.4 also throws NoClassDefFoundError and UnsatisfiedLinkError
			}
		}
		sunProblematicClasses = (Class[]) classes.toArray(new Class[0]);
	}

	/**
	 * http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=5012949
	 * Implementation note:
	 * 	we can compare classes with == since they will always be loaded from the same ClassLoader
	 * 	(they are "low" in the hierarchy)
	 */
	private static int skipClassDueToSunJVMBug(Class<?> clazz) {
		for (int i = 0; i < sunProblematicClasses.length; ++i) {
			Class<?> sunPbClass = sunProblematicClasses[i];
			if (clazz == sunPbClass) {
				return sunProblematicClassesSizes.get(clazz.getName()).intValue();
			}
		}
		return 0;
	}

	/*
	 * Very incomplete, but better than nothing...
	 */
	// See http://docs.oracle.com/javase/7/docs/api/constant-values.html for JDK's String constants
	// a bit of help on the html docs: grep -r "public static final&nbsp;<a href=\"" . | uniq
	// a bit of help from docs: e.g. http://docs.oracle.com/javase/7/docs/api/java/lang/class-use/Float.html
	@SuppressWarnings("deprecation")
	private static boolean isSharedFlyweight(Object obj) {
		if (obj == null || Enum.class.isInstance(obj) || Class.class.isInstance(obj) || javax.print.attribute.EnumSyntax.class.isInstance(obj) ||
				Character.UnicodeBlock.class.isInstance(obj) ||
				java.nio.ByteOrder.class.isInstance(obj) ||
				java.nio.channels.FileChannel.MapMode.class.isInstance(obj) ||
				java.nio.charset.CoderResult.class.isInstance(obj) ||
				java.nio.charset.CodingErrorAction.class.isInstance(obj) ||
				java.text.DateFormat.Field.class.isInstance(obj) || java.text.MessageFormat.Field.class.isInstance(obj) || java.text.NumberFormat.Field.class.isInstance(obj) ||
				javax.management.openmbean.SimpleType.class.isInstance(obj) ||
				javax.security.auth.login.AppConfigurationEntry.LoginModuleControlFlag.class.isInstance(obj) ||
				DatatypeConstants.Field.class.isInstance(obj)
				) {
			return true;
		}
		if (obj == Boolean.TRUE || obj == Boolean.FALSE) {
			return true;
		}
		if (/*obj == Locale.ROOT ||*//*Java 6*/
				obj == Locale.ENGLISH  || obj == Locale.FRENCH || obj == Locale.GERMAN || obj == Locale.ITALIAN ||
				obj == Locale.JAPANESE || obj == Locale.KOREAN || obj == Locale.CHINESE ||
				obj == Locale.SIMPLIFIED_CHINESE || obj == Locale.TRADITIONAL_CHINESE  || obj == Locale.FRANCE ||
				obj == Locale.GERMANY  || obj == Locale.ITALY || obj == Locale.JAPAN   ||
				obj == Locale.KOREA    || obj == Locale.CHINA || obj == Locale.PRC     || obj == Locale.TAIWAN ||
				obj == Locale.UK       || obj == Locale.US    || obj == Locale.CANADA  || obj == Locale.CANADA_FRENCH) {
			return true;
		}
		if (obj == Collections.EMPTY_SET || obj == Collections.EMPTY_LIST || obj == Collections.EMPTY_MAP) {
			return true;
		}
		if (obj == BigInteger.ZERO || obj == BigInteger.ONE || obj == BigInteger.TEN
				|| obj == java.security.spec.RSAKeyGenParameterSpec.F0 || obj == java.security.spec.RSAKeyGenParameterSpec.F4) {
			return true;
		}
		if (obj == BigDecimal.ZERO || obj == BigDecimal.ONE || obj == BigDecimal.TEN) {
			return true;
		}
		if (obj == MathContext.UNLIMITED || obj == MathContext.DECIMAL32 || obj == MathContext.DECIMAL64 || obj == MathContext.DECIMAL128) {
			return true;
		}
		if (obj == System.in || obj == System.out || obj == System.err) {
			return true;
		}
		if (obj == String.CASE_INSENSITIVE_ORDER) {
			return true;
		}
		if (obj == java.net.Proxy.NO_PROXY) {
			return true;
		}
		if (obj == java.util.logging.Logger.global || obj == java.util.logging.Level.OFF || obj == java.util.logging.Level.SEVERE || obj == java.util.logging.Level.WARNING || obj == java.util.logging.Level.INFO || obj == java.util.logging.Level.CONFIG || obj == java.util.logging.Level.FINE || obj == java.util.logging.Level.FINER || obj == java.util.logging.Level.FINEST || obj == java.util.logging.Level.ALL) {
			return true;
		}
		if (obj == java.beans.DesignMode.PROPERTYNAME ||
				obj == ManagementFactory.CLASS_LOADING_MXBEAN_NAME || obj == ManagementFactory.COMPILATION_MXBEAN_NAME || obj == ManagementFactory.GARBAGE_COLLECTOR_MXBEAN_DOMAIN_TYPE || obj == ManagementFactory.MEMORY_MANAGER_MXBEAN_DOMAIN_TYPE || obj == ManagementFactory.MEMORY_MXBEAN_NAME || obj == ManagementFactory.MEMORY_POOL_MXBEAN_DOMAIN_TYPE || obj == ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME || obj == ManagementFactory.RUNTIME_MXBEAN_NAME || obj == ManagementFactory.THREAD_MXBEAN_NAME ||
				obj == MemoryNotificationInfo.MEMORY_COLLECTION_THRESHOLD_EXCEEDED || obj == MemoryNotificationInfo.MEMORY_THRESHOLD_EXCEEDED ||
				obj == java.rmi.server.LoaderHandler.packagePrefix || obj == java.rmi.server.RemoteRef.packagePrefix ||
				obj == java.io.File.separator || obj == java.io.File.pathSeparator ||
				obj == java.util.jar.JarFile.MANIFEST_NAME ||
				obj == Pack200.Packer.CLASS_ATTRIBUTE_PFX || obj == Pack200.Packer.CODE_ATTRIBUTE_PFX || obj == Pack200.Packer.DEFLATE_HINT || obj == Pack200.Packer.EFFORT || obj == Pack200.Packer.ERROR || obj == Pack200.Packer.FALSE || obj == Pack200.Packer.FIELD_ATTRIBUTE_PFX || obj == Pack200.Packer.KEEP || obj == Pack200.Packer.KEEP_FILE_ORDER || obj == Pack200.Packer.LATEST || obj == Pack200.Packer.METHOD_ATTRIBUTE_PFX || obj == Pack200.Packer.MODIFICATION_TIME || obj == Pack200.Packer.PASS || obj == Pack200.Packer.PASS_FILE_PFX || obj == Pack200.Packer.PROGRESS || obj == Pack200.Packer.SEGMENT_LIMIT || obj == Pack200.Packer.STRIP || obj == Pack200.Packer.TRUE || obj == Pack200.Packer.UNKNOWN_ATTRIBUTE ||
				obj == Pack200.Unpacker.DEFLATE_HINT || obj == Pack200.Unpacker.FALSE || obj == Pack200.Unpacker.KEEP || obj == Pack200.Unpacker.PROGRESS || obj == Pack200.Unpacker.TRUE ||
				/*obj == java.util.logging.Logger.GLOBAL_LOGGER_NAME || Java 6*/ obj == java.util.logging.LogManager.LOGGING_MXBEAN_NAME ||
				obj == AccessibleContext.ACCESSIBLE_ACTION_PROPERTY || obj == AccessibleContext.ACCESSIBLE_ACTIVE_DESCENDANT_PROPERTY || obj == AccessibleContext.ACCESSIBLE_CARET_PROPERTY || obj == AccessibleContext.ACCESSIBLE_CHILD_PROPERTY || obj == AccessibleContext.ACCESSIBLE_COMPONENT_BOUNDS_CHANGED || obj == AccessibleContext.ACCESSIBLE_DESCRIPTION_PROPERTY || obj == AccessibleContext.ACCESSIBLE_HYPERTEXT_OFFSET || obj == AccessibleContext.ACCESSIBLE_INVALIDATE_CHILDREN || obj == AccessibleContext.ACCESSIBLE_NAME_PROPERTY || obj == AccessibleContext.ACCESSIBLE_SELECTION_PROPERTY || obj == AccessibleContext.ACCESSIBLE_STATE_PROPERTY || obj == AccessibleContext.ACCESSIBLE_TABLE_CAPTION_CHANGED || obj == AccessibleContext.ACCESSIBLE_TABLE_COLUMN_DESCRIPTION_CHANGED || obj == AccessibleContext.ACCESSIBLE_TABLE_COLUMN_HEADER_CHANGED || obj == AccessibleContext.ACCESSIBLE_TABLE_MODEL_CHANGED || obj == AccessibleContext.ACCESSIBLE_TABLE_ROW_DESCRIPTION_CHANGED || obj == AccessibleContext.ACCESSIBLE_TABLE_ROW_HEADER_CHANGED || obj == AccessibleContext.ACCESSIBLE_TABLE_SUMMARY_CHANGED || obj == AccessibleContext.ACCESSIBLE_TEXT_ATTRIBUTES_CHANGED || obj == AccessibleContext.ACCESSIBLE_TEXT_PROPERTY || obj == AccessibleContext.ACCESSIBLE_VALUE_PROPERTY || obj == AccessibleContext.ACCESSIBLE_VISIBLE_DATA_PROPERTY ||
				obj == AccessibleRelation.CHILD_NODE_OF || obj == AccessibleRelation.CHILD_NODE_OF_PROPERTY || obj == AccessibleRelation.CONTROLLED_BY || obj == AccessibleRelation.CONTROLLED_BY_PROPERTY || obj == AccessibleRelation.CONTROLLER_FOR || obj == AccessibleRelation.CONTROLLER_FOR_PROPERTY || obj == AccessibleRelation.EMBEDDED_BY || obj == AccessibleRelation.EMBEDDED_BY_PROPERTY || obj == AccessibleRelation.EMBEDS || obj == AccessibleRelation.EMBEDS_PROPERTY || obj == AccessibleRelation.FLOWS_FROM || obj == AccessibleRelation.FLOWS_FROM_PROPERTY || obj == AccessibleRelation.FLOWS_TO || obj == AccessibleRelation.FLOWS_TO_PROPERTY || obj == AccessibleRelation.LABEL_FOR || obj == AccessibleRelation.LABEL_FOR_PROPERTY || obj == AccessibleRelation.LABELED_BY || obj == AccessibleRelation.LABELED_BY_PROPERTY || obj == AccessibleRelation.MEMBER_OF || obj == AccessibleRelation.MEMBER_OF_PROPERTY || obj == AccessibleRelation.PARENT_WINDOW_OF || obj == AccessibleRelation.PARENT_WINDOW_OF_PROPERTY || obj == AccessibleRelation.SUBWINDOW_OF || obj == AccessibleRelation.SUBWINDOW_OF_PROPERTY ||
				obj == javax.imageio.metadata.IIOMetadataFormatImpl.standardMetadataFormatName || obj == javax.management.AttributeChangeNotification.ATTRIBUTE_CHANGE ||
				/*obj == javax.management.JMX.* || Java 6*/
				obj == javax.management.MBeanServerNotification.REGISTRATION_NOTIFICATION || obj == javax.management.MBeanServerNotification.UNREGISTRATION_NOTIFICATION ||
				obj == MonitorNotification.OBSERVED_ATTRIBUTE_ERROR || obj == MonitorNotification.OBSERVED_ATTRIBUTE_TYPE_ERROR || obj == MonitorNotification.OBSERVED_OBJECT_ERROR || obj == MonitorNotification.RUNTIME_ERROR || obj == MonitorNotification.STRING_TO_COMPARE_VALUE_DIFFERED || obj == MonitorNotification.STRING_TO_COMPARE_VALUE_MATCHED || obj == MonitorNotification.THRESHOLD_ERROR || obj == MonitorNotification.THRESHOLD_HIGH_VALUE_EXCEEDED || obj == MonitorNotification.THRESHOLD_LOW_VALUE_EXCEEDED || obj == MonitorNotification.THRESHOLD_VALUE_EXCEEDED ||
				obj == RelationNotification.RELATION_BASIC_CREATION || obj == RelationNotification.RELATION_BASIC_REMOVAL || obj == RelationNotification.RELATION_BASIC_UPDATE || obj == RelationNotification.RELATION_MBEAN_CREATION || obj == RelationNotification.RELATION_MBEAN_REMOVAL || obj == RelationNotification.RELATION_MBEAN_UPDATE ||
				obj == JMXConnectionNotification.CLOSED || obj == JMXConnectionNotification.FAILED || obj == JMXConnectionNotification.NOTIFS_LOST || obj == JMXConnectionNotification.OPENED ||
				obj == javax.management.remote.JMXConnector.CREDENTIALS ||
				obj == JMXConnectorFactory.DEFAULT_CLASS_LOADER || obj == JMXConnectorFactory.PROTOCOL_PROVIDER_CLASS_LOADER || obj == JMXConnectorFactory.PROTOCOL_PROVIDER_PACKAGES ||
				obj == javax.management.remote.JMXConnectorServer.AUTHENTICATOR ||
				obj == JMXConnectorServerFactory.DEFAULT_CLASS_LOADER || obj == JMXConnectorServerFactory.DEFAULT_CLASS_LOADER_NAME || obj == JMXConnectorServerFactory.PROTOCOL_PROVIDER_CLASS_LOADER || obj == JMXConnectorServerFactory.PROTOCOL_PROVIDER_PACKAGES ||
				obj == RMIConnectorServer.JNDI_REBIND_ATTRIBUTE || obj == RMIConnectorServer.RMI_CLIENT_SOCKET_FACTORY_ATTRIBUTE || obj == RMIConnectorServer.RMI_SERVER_SOCKET_FACTORY_ATTRIBUTE ||
				obj == Context.APPLET || obj == Context.AUTHORITATIVE || obj == Context.BATCHSIZE || obj == Context.DNS_URL || obj == Context.INITIAL_CONTEXT_FACTORY || obj == Context.LANGUAGE || obj == Context.OBJECT_FACTORIES || obj == Context.PROVIDER_URL || obj == Context.REFERRAL || obj == Context.SECURITY_AUTHENTICATION || obj == Context.SECURITY_CREDENTIALS || obj == Context.SECURITY_PRINCIPAL || obj == Context.SECURITY_PROTOCOL || obj == Context.STATE_FACTORIES || obj == Context.URL_PKG_PREFIXES ||
				obj == javax.naming.ldap.LdapContext.CONTROL_FACTORIES ||
				obj == javax.naming.ldap.ManageReferralControl.OID || obj == javax.naming.ldap.PagedResultsControl.OID || obj == javax.naming.ldap.PagedResultsResponseControl.OID || obj == javax.naming.ldap.SortControl.OID || obj == javax.naming.ldap.SortResponseControl.OID || obj == javax.naming.ldap.StartTlsRequest.OID || obj == javax.naming.ldap.StartTlsResponse.OID ||
				obj == javax.naming.spi.NamingManager.CPE ||
				obj == javax.print.ServiceUIFactory.DIALOG_UI || obj == javax.print.ServiceUIFactory.JCOMPONENT_UI || obj == javax.print.ServiceUIFactory.JDIALOG_UI || obj == javax.print.ServiceUIFactory.PANEL_UI ||
				/*obj == javax.script.ScriptEngine.* || Java 6*/
				obj == javax.security.auth.x500.X500Principal.CANONICAL || obj == javax.security.auth.x500.X500Principal.RFC1779 || obj == javax.security.auth.x500.X500Principal.RFC2253 ||
				/*obj == Sasl.CREDENTIALS || Java 6*/ obj == Sasl.MAX_BUFFER || obj == Sasl.POLICY_FORWARD_SECRECY || obj == Sasl.POLICY_NOACTIVE || obj == Sasl.POLICY_NOANONYMOUS || obj == Sasl.POLICY_NODICTIONARY || obj == Sasl.POLICY_NOPLAINTEXT || obj == Sasl.POLICY_PASS_CREDENTIALS || obj == Sasl.QOP || obj == Sasl.RAW_SEND_SIZE || obj == Sasl.REUSE || obj == Sasl.SERVER_AUTH || obj == Sasl.STRENGTH ||
				obj == javax.sql.rowset.WebRowSet.PUBLIC_XML_SCHEMA || obj == javax.sql.rowset.WebRowSet.SCHEMA_SYSTEM_ID ||
				obj == javax.sql.rowset.spi.SyncFactory.ROWSET_SYNC_PROVIDER || obj == javax.sql.rowset.spi.SyncFactory.ROWSET_SYNC_PROVIDER_VERSION || obj == javax.sql.rowset.spi.SyncFactory.ROWSET_SYNC_VENDOR ||
				obj == XMLConstants.DEFAULT_NS_PREFIX || obj == XMLConstants.FEATURE_SECURE_PROCESSING || obj == XMLConstants.NULL_NS_URI || obj == XMLConstants.RELAXNG_NS_URI || obj == XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI || obj == XMLConstants.W3C_XML_SCHEMA_NS_URI || obj == XMLConstants.W3C_XPATH_DATATYPE_NS_URI || obj == XMLConstants.XML_DTD_NS_URI || obj == XMLConstants.XML_NS_PREFIX || obj == XMLConstants.XML_NS_URI || obj == XMLConstants.XMLNS_ATTRIBUTE || obj == XMLConstants.XMLNS_ATTRIBUTE_NS_URI ||
				/*obj == javax.xml.bind.JAXBContext.JAXB_CONTEXT_FACTORY || Java 6*/
				/*obj == javax.xml.bind.Marshaller.* || Java 6*/
				/*obj == javax.xml.bind.annotation.XmlSchema.NO_LOCATION || java 6*/
				/*obj == javax.xml.crypto.dsig.*.* || Java 6*/
				obj == javax.xml.datatype.DatatypeFactory.DATATYPEFACTORY_PROPERTY ||
				/*obj == javax.xml.soap.SOAPConstants.* || Java 6*/
				/*obj == javax.xml.soap.SOAPMessage.* || Java 6*/
				/*obj == javax.xml.stream.*.* || Java 6*/
				obj == OutputKeys.CDATA_SECTION_ELEMENTS || obj == OutputKeys.DOCTYPE_PUBLIC || obj == OutputKeys.DOCTYPE_SYSTEM || obj == OutputKeys.ENCODING || obj == OutputKeys.INDENT || obj == OutputKeys.MEDIA_TYPE || obj == OutputKeys.METHOD || obj == OutputKeys.OMIT_XML_DECLARATION || obj == OutputKeys.STANDALONE || obj == OutputKeys.VERSION ||
				obj == javax.xml.transform.Result.PI_DISABLE_OUTPUT_ESCAPING || obj == javax.xml.transform.Result.PI_ENABLE_OUTPUT_ESCAPING ||
				obj == javax.xml.transform.dom.DOMResult.FEATURE || obj == javax.xml.transform.dom.DOMSource.FEATURE || obj == javax.xml.transform.sax.SAXResult.FEATURE || obj == javax.xml.transform.sax.SAXSource.FEATURE || obj == javax.xml.transform.sax.SAXTransformerFactory.FEATURE || obj == javax.xml.transform.sax.SAXTransformerFactory.FEATURE_XMLFILTER || /*obj == javax.xml.transform.stax.StAXResult.FEATURE || obj == javax.xml.transform.stax.StAXSource.FEATURE || Java 6*/ obj == javax.xml.transform.stream.StreamResult.FEATURE || obj == javax.xml.transform.stream.StreamSource.FEATURE ||
				/*obj == javax.xml.ws.*.* || Java 6*/
				obj == javax.xml.xpath.XPathConstants.DOM_OBJECT_MODEL || obj == javax.xml.xpath.XPathFactory.DEFAULT_OBJECT_MODEL_URI || obj == javax.xml.xpath.XPathFactory.DEFAULT_PROPERTY_NAME ||
				obj == org.w3c.dom.bootstrap.DOMImplementationRegistry.PROPERTY ||
				obj == org.xml.sax.helpers.NamespaceSupport.NSDECL || obj == org.xml.sax.helpers.NamespaceSupport.XMLNS) {
			return true;
		}
		if (obj == java.awt.AlphaComposite.Clear
				|| obj == java.awt.AlphaComposite.Src
				|| obj == java.awt.AlphaComposite.Dst
				|| obj == java.awt.AlphaComposite.SrcOver
				|| obj == java.awt.AlphaComposite.DstOver
				|| obj == java.awt.AlphaComposite.SrcIn
				|| obj == java.awt.AlphaComposite.DstIn
				|| obj == java.awt.AlphaComposite.SrcOut
				|| obj == java.awt.AlphaComposite.DstOut
				|| obj == java.awt.AlphaComposite.SrcAtop
				|| obj == java.awt.AlphaComposite.DstAtop
				|| obj == java.awt.AlphaComposite.Xor) {
			return true;
		}
		if (obj == java.awt.Color.WHITE
				|| obj == java.awt.Color.LIGHT_GRAY || obj == java.awt.Color.GRAY || obj == java.awt.Color.DARK_GRAY
				|| obj == java.awt.Color.BLACK || obj == java.awt.Color.RED
				|| obj == java.awt.Color.PINK || obj == java.awt.Color.ORANGE
				|| obj == java.awt.Color.YELLOW || obj == java.awt.Color.GREEN
				|| obj == java.awt.Color.MAGENTA || obj == java.awt.Color.CYAN
				|| obj == java.awt.Color.BLUE) {
			return true;
		}
		if (obj == java.io.FileDescriptor.in || obj == java.io.FileDescriptor.out || obj == java.io.FileDescriptor.err || obj == java.io.ObjectStreamClass.NO_FIELDS) {
			return true;
		}
		if (obj == java.security.spec.ECPoint.POINT_INFINITY
				|| obj == java.security.spec.MGF1ParameterSpec.SHA1 || obj == java.security.spec.MGF1ParameterSpec.SHA256 || obj == java.security.spec.MGF1ParameterSpec.SHA384 || obj == java.security.spec.MGF1ParameterSpec.SHA512
				|| obj == java.security.spec.PSSParameterSpec.DEFAULT) {
			return true;
		}
		if (obj == java.text.AttributedCharacterIterator.Attribute.LANGUAGE || obj == java.text.AttributedCharacterIterator.Attribute.READING || obj == java.text.AttributedCharacterIterator.Attribute.INPUT_METHOD_SEGMENT) {
			return true;
		}
		if (obj == java.util.jar.Attributes.Name.MANIFEST_VERSION || obj == java.util.jar.Attributes.Name.SIGNATURE_VERSION || obj == java.util.jar.Attributes.Name.CONTENT_TYPE || obj == java.util.jar.Attributes.Name.CLASS_PATH || obj == java.util.jar.Attributes.Name.MAIN_CLASS || obj == java.util.jar.Attributes.Name.SEALED || obj == java.util.jar.Attributes.Name.EXTENSION_LIST || obj == java.util.jar.Attributes.Name.EXTENSION_NAME || obj == java.util.jar.Attributes.Name.EXTENSION_INSTALLATION || obj == java.util.jar.Attributes.Name.IMPLEMENTATION_TITLE || obj == java.util.jar.Attributes.Name.IMPLEMENTATION_VERSION || obj == java.util.jar.Attributes.Name.IMPLEMENTATION_VENDOR || obj == java.util.jar.Attributes.Name.IMPLEMENTATION_VENDOR_ID || obj == java.util.jar.Attributes.Name.IMPLEMENTATION_URL || obj == java.util.jar.Attributes.Name.SPECIFICATION_TITLE || obj == java.util.jar.Attributes.Name.SPECIFICATION_VERSION || obj == java.util.jar.Attributes.Name.SPECIFICATION_VENDOR) {
			return true;
		}
		if (obj == AccessibleRole.ALERT || obj == AccessibleRole.COLUMN_HEADER || obj == AccessibleRole.CANVAS || obj == AccessibleRole.COMBO_BOX || obj == AccessibleRole.DESKTOP_ICON /*|| obj == AccessibleRole.HTML_CONTAINER */|| obj == AccessibleRole.INTERNAL_FRAME || obj == AccessibleRole.DESKTOP_PANE || obj == AccessibleRole.OPTION_PANE || obj == AccessibleRole.WINDOW || obj == AccessibleRole.FRAME || obj == AccessibleRole.DIALOG || obj == AccessibleRole.COLOR_CHOOSER || obj == AccessibleRole.DIRECTORY_PANE || obj == AccessibleRole.FILE_CHOOSER || obj == AccessibleRole.FILLER || obj == AccessibleRole.HYPERLINK || obj == AccessibleRole.ICON || obj == AccessibleRole.LABEL || obj == AccessibleRole.ROOT_PANE || obj == AccessibleRole.GLASS_PANE || obj == AccessibleRole.LAYERED_PANE || obj == AccessibleRole.LIST || obj == AccessibleRole.LIST_ITEM || obj == AccessibleRole.MENU_BAR || obj == AccessibleRole.POPUP_MENU || obj == AccessibleRole.MENU || obj == AccessibleRole.MENU_ITEM || obj == AccessibleRole.SEPARATOR || obj == AccessibleRole.PAGE_TAB_LIST || obj == AccessibleRole.PAGE_TAB || obj == AccessibleRole.PANEL || obj == AccessibleRole.PROGRESS_BAR || obj == AccessibleRole.PASSWORD_TEXT || obj == AccessibleRole.PUSH_BUTTON || obj == AccessibleRole.TOGGLE_BUTTON || obj == AccessibleRole.CHECK_BOX || obj == AccessibleRole.RADIO_BUTTON || obj == AccessibleRole.ROW_HEADER || obj == AccessibleRole.SCROLL_PANE || obj == AccessibleRole.SCROLL_BAR || obj == AccessibleRole.VIEWPORT || obj == AccessibleRole.SLIDER || obj == AccessibleRole.SPLIT_PANE || obj == AccessibleRole.TABLE || obj == AccessibleRole.TEXT || obj == AccessibleRole.TREE || obj == AccessibleRole.TOOL_BAR || obj == AccessibleRole.TOOL_TIP || obj == AccessibleRole.AWT_COMPONENT || obj == AccessibleRole.SWING_COMPONENT || obj == AccessibleRole.UNKNOWN || obj == AccessibleRole.STATUS_BAR || obj == AccessibleRole.DATE_EDITOR || obj == AccessibleRole.SPIN_BOX || obj == AccessibleRole.FONT_CHOOSER || obj == AccessibleRole.GROUP_BOX || obj == AccessibleRole.HEADER || obj == AccessibleRole.FOOTER || obj == AccessibleRole.PARAGRAPH || obj == AccessibleRole.RULER || obj == AccessibleRole.EDITBAR || obj == AccessibleRole.PROGRESS_MONITOR
				|| obj == AccessibleState.ACTIVE || obj == AccessibleState.PRESSED || obj == AccessibleState.ARMED || obj == AccessibleState.BUSY || obj == AccessibleState.CHECKED || obj == AccessibleState.EDITABLE || obj == AccessibleState.EXPANDABLE || obj == AccessibleState.COLLAPSED || obj == AccessibleState.EXPANDED || obj == AccessibleState.ENABLED || obj == AccessibleState.FOCUSABLE || obj == AccessibleState.FOCUSED || obj == AccessibleState.ICONIFIED || obj == AccessibleState.MODAL || obj == AccessibleState.OPAQUE || obj == AccessibleState.RESIZABLE || obj == AccessibleState.MULTISELECTABLE || obj == AccessibleState.SELECTABLE || obj == AccessibleState.SELECTED || obj == AccessibleState.SHOWING || obj == AccessibleState.VISIBLE || obj == AccessibleState.VERTICAL || obj == AccessibleState.HORIZONTAL || obj == AccessibleState.SINGLE_LINE || obj == AccessibleState.MULTI_LINE || obj == AccessibleState.TRANSIENT || obj == AccessibleState.MANAGES_DESCENDANTS || obj == AccessibleState.INDETERMINATE || obj == AccessibleState.TRUNCATED) {
			return true;
		}
		if (obj == javax.crypto.spec.OAEPParameterSpec.DEFAULT || obj == javax.crypto.spec.PSource.PSpecified.DEFAULT) {
			return true;
		}
		if (obj == javax.imageio.plugins.jpeg.JPEGHuffmanTable.StdDCLuminance || obj == javax.imageio.plugins.jpeg.JPEGHuffmanTable.StdDCChrominance || obj == javax.imageio.plugins.jpeg.JPEGHuffmanTable.StdACLuminance || obj == javax.imageio.plugins.jpeg.JPEGHuffmanTable.StdACChrominance
				|| obj == javax.imageio.plugins.jpeg.JPEGQTable.K1Luminance || obj == javax.imageio.plugins.jpeg.JPEGQTable.K1Div2Luminance || obj == javax.imageio.plugins.jpeg.JPEGQTable.K2Chrominance || obj == javax.imageio.plugins.jpeg.JPEGQTable.K2Div2Chrominance) {
			return true;
		}
		if (obj == DatatypeConstants.DATETIME || obj == DatatypeConstants.TIME || obj == DatatypeConstants.DATE || obj == DatatypeConstants.GYEARMONTH || obj == DatatypeConstants.GMONTHDAY || obj == DatatypeConstants.GYEAR || obj == DatatypeConstants.GMONTH || obj == DatatypeConstants.GDAY || obj == DatatypeConstants.DURATION || obj == DatatypeConstants.DURATION_DAYTIME || obj == DatatypeConstants.DURATION_YEARMONTH
				|| obj == javax.xml.xpath.XPathConstants.NUMBER || obj == javax.xml.xpath.XPathConstants.STRING || obj == javax.xml.xpath.XPathConstants.BOOLEAN || obj == javax.xml.xpath.XPathConstants.NODESET || obj == javax.xml.xpath.XPathConstants.NODE || obj == javax.xml.xpath.XPathConstants.DOM_OBJECT_MODEL) {
			return true;
		}
		if (obj == DocFlavor.BYTE_ARRAY.TEXT_PLAIN_HOST || obj == DocFlavor.BYTE_ARRAY.TEXT_PLAIN_UTF_8 || obj == DocFlavor.BYTE_ARRAY.TEXT_PLAIN_UTF_16 || obj == DocFlavor.BYTE_ARRAY.TEXT_PLAIN_UTF_16BE || obj == DocFlavor.BYTE_ARRAY.TEXT_PLAIN_UTF_16LE || obj == DocFlavor.BYTE_ARRAY.TEXT_PLAIN_US_ASCII || obj == DocFlavor.BYTE_ARRAY.TEXT_HTML_HOST || obj == DocFlavor.BYTE_ARRAY.TEXT_HTML_UTF_8 || obj == DocFlavor.BYTE_ARRAY.TEXT_HTML_UTF_16 || obj == DocFlavor.BYTE_ARRAY.TEXT_HTML_UTF_16BE || obj == DocFlavor.BYTE_ARRAY.TEXT_HTML_UTF_16LE || obj == DocFlavor.BYTE_ARRAY.TEXT_HTML_US_ASCII || obj == DocFlavor.BYTE_ARRAY.PDF || obj == DocFlavor.BYTE_ARRAY.POSTSCRIPT || obj == DocFlavor.BYTE_ARRAY.PCL || obj == DocFlavor.BYTE_ARRAY.GIF || obj == DocFlavor.BYTE_ARRAY.JPEG || obj == DocFlavor.BYTE_ARRAY.PNG || obj == DocFlavor.BYTE_ARRAY.AUTOSENSE
				|| obj == DocFlavor.INPUT_STREAM.TEXT_PLAIN_HOST || obj == DocFlavor.INPUT_STREAM.TEXT_PLAIN_UTF_8 || obj == DocFlavor.INPUT_STREAM.TEXT_PLAIN_UTF_16 || obj == DocFlavor.INPUT_STREAM.TEXT_PLAIN_UTF_16BE || obj == DocFlavor.INPUT_STREAM.TEXT_PLAIN_UTF_16LE || obj == DocFlavor.INPUT_STREAM.TEXT_PLAIN_US_ASCII || obj == DocFlavor.INPUT_STREAM.TEXT_HTML_HOST || obj == DocFlavor.INPUT_STREAM.TEXT_HTML_UTF_8 || obj == DocFlavor.INPUT_STREAM.TEXT_HTML_UTF_16 || obj == DocFlavor.INPUT_STREAM.TEXT_HTML_UTF_16BE || obj == DocFlavor.INPUT_STREAM.TEXT_HTML_UTF_16LE || obj == DocFlavor.INPUT_STREAM.TEXT_HTML_US_ASCII || obj == DocFlavor.INPUT_STREAM.PDF || obj == DocFlavor.INPUT_STREAM.POSTSCRIPT || obj == DocFlavor.INPUT_STREAM.PCL || obj == DocFlavor.INPUT_STREAM.GIF || obj == DocFlavor.INPUT_STREAM.JPEG || obj == DocFlavor.INPUT_STREAM.PNG || obj == DocFlavor.INPUT_STREAM.AUTOSENSE
				|| obj == DocFlavor.URL.TEXT_PLAIN_HOST || obj == DocFlavor.URL.TEXT_PLAIN_UTF_8 || obj == DocFlavor.URL.TEXT_PLAIN_UTF_16 || obj == DocFlavor.URL.TEXT_PLAIN_UTF_16BE || obj == DocFlavor.URL.TEXT_PLAIN_UTF_16LE || obj == DocFlavor.URL.TEXT_PLAIN_US_ASCII || obj == DocFlavor.URL.TEXT_HTML_HOST || obj == DocFlavor.URL.TEXT_HTML_UTF_8 || obj == DocFlavor.URL.TEXT_HTML_UTF_16 || obj == DocFlavor.URL.TEXT_HTML_UTF_16BE || obj == DocFlavor.URL.TEXT_HTML_UTF_16LE || obj == DocFlavor.URL.TEXT_HTML_US_ASCII || obj == DocFlavor.URL.PDF || obj == DocFlavor.URL.POSTSCRIPT || obj == DocFlavor.URL.PCL || obj == DocFlavor.URL.GIF || obj == DocFlavor.URL.JPEG || obj == DocFlavor.URL.PNG || obj == DocFlavor.URL.AUTOSENSE
				|| obj == DocFlavor.CHAR_ARRAY.TEXT_PLAIN || obj == DocFlavor.CHAR_ARRAY.TEXT_HTML
				|| obj == DocFlavor.STRING.TEXT_PLAIN || obj == DocFlavor.STRING.TEXT_HTML
				|| obj == DocFlavor.READER.TEXT_PLAIN || obj == DocFlavor.READER.TEXT_HTML
				|| obj == DocFlavor.SERVICE_FORMATTED.RENDERABLE_IMAGE || obj == DocFlavor.SERVICE_FORMATTED.PRINTABLE || obj == DocFlavor.SERVICE_FORMATTED.PAGEABLE) {
			return true;
		}
		if (obj == javax.sound.sampled.AudioFileFormat.Type.WAVE || obj == javax.sound.sampled.AudioFileFormat.Type.AU || obj == javax.sound.sampled.AudioFileFormat.Type.AIFF || obj == javax.sound.sampled.AudioFileFormat.Type.AIFC || obj == javax.sound.sampled.AudioFileFormat.Type.SND
				|| obj == javax.sound.sampled.BooleanControl.Type.MUTE || obj == javax.sound.sampled.BooleanControl.Type.APPLY_REVERB
				|| obj == javax.sound.sampled.EnumControl.Type.REVERB
				|| obj == javax.sound.sampled.FloatControl.Type.MASTER_GAIN || obj == javax.sound.sampled.FloatControl.Type.AUX_SEND || obj == javax.sound.sampled.FloatControl.Type.AUX_RETURN || obj == javax.sound.sampled.FloatControl.Type.REVERB_SEND || obj == javax.sound.sampled.FloatControl.Type.REVERB_RETURN || obj == javax.sound.sampled.FloatControl.Type.VOLUME || obj == javax.sound.sampled.FloatControl.Type.PAN || obj == javax.sound.sampled.FloatControl.Type.BALANCE || obj == javax.sound.sampled.FloatControl.Type.SAMPLE_RATE
				|| obj == javax.sound.sampled.LineEvent.Type.OPEN || obj == javax.sound.sampled.LineEvent.Type.CLOSE || obj == javax.sound.sampled.LineEvent.Type.START || obj == javax.sound.sampled.LineEvent.Type.STOP
				|| obj == javax.sound.sampled.Port.Info.MICROPHONE || obj == javax.sound.sampled.Port.Info.LINE_IN || obj == javax.sound.sampled.Port.Info.COMPACT_DISC || obj == javax.sound.sampled.Port.Info.SPEAKER || obj == javax.sound.sampled.Port.Info.HEADPHONE || obj == javax.sound.sampled.Port.Info.LINE_OUT) {
			return true;
		}
		return false;
	}
} // end of class
// ----------------------------------------------------------------------------
