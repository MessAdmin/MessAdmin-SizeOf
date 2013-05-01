/**
 * 
 */
package clime.messadmin.providers.sizeof;

import clime.messadmin.providers.spi.SizeOfProvider;

/**
 * @author C&eacute;drik LIME
 */
public class ObjectProfilerProvider implements SizeOfProvider {

	/**
	 * 
	 */
	public ObjectProfilerProvider() {
		super();
	}

	/**
	 * {@inheritDoc}
	 */
	public int getPriority() {
		return 0;
	}

	/**
	 * {@inheritDoc}
	 */
	public long sizeof(Object objectToSize) {
		return ObjectProfiler.sizeof(objectToSize);
	}

}
