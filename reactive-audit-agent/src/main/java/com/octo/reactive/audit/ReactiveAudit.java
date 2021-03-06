/*
 * Copyright 2014 OCTO Technology
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.octo.reactive.audit;

import com.octo.reactive.audit.lib.*;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.InvalidPathException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.*;
import java.util.regex.Pattern;

import static com.octo.reactive.audit.LoadParams.*;

@SuppressWarnings("RefusedBequest")
public class ReactiveAudit
{
	/**
	 * The singleton with the current parameters.
	 */
	public static final ReactiveAudit        config            = new ReactiveAudit();
	/**
	 * A transaction with 'strict' parameters.
	 */
	public static final Transaction          strict            =
			config.begin()
					.throwExceptions(true)
					.threadPattern(".*")
					.bootStrapDelay(0)
					.seal();
	/**
	 * A transaction with 'log only' parameters.
	 */
	public static final Transaction          logOnly           =
			config.begin()
					.throwExceptions(false)
					.logOutput(DEFAULT_LOG_OUTPUT, DEFAULT_LOG_FORMAT, DEFAULT_LOG_SIZE)
					.threadPattern(DEFAULT_THREAD_PATTERN)
					.bootStrapDelay(0)
					.seal();
	/**
	 * A transaction with 'off' audit.
	 */
	public static final Transaction          off               =
			config.begin()
					.throwExceptions(false)
					.threadPattern("(?!)")
					.bootStrapDelay(0)
					.seal();
	// Help to rename the package
	public static final String               auditPackageName  = ReactiveAudit.class.getPackage().getName();
	public final        Logger               logger            = Logger.getAnonymousLogger();
	// FIXME : Try to use a fake logger to test Jboss. Jboss has a problem with Aspectj :-(
//	public final        FakeLogger          logger             = new FakeLogger();
	private final       LongAdder            statLow           = new LongAdder();
	private final       LongAdder            statMedium        = new LongAdder();
	private final       LongAdder            statHigh          = new LongAdder();
	private final       AtomicInteger        statMaxThread     = new AtomicInteger(0);
	private final       ThreadLocal<Integer> suppressAudit     = new ThreadLocal<Integer>()
	{
		@Override
		protected Integer initialValue()
		{
			return 0;
		}
	};
	private final       Stack<Transaction>   stack             = new Stack<Transaction>();
	private final       HistoryStackElement  history           = new HistoryStackElement();
	private final       Set<String>          historyThreadName = Collections.synchronizedSet(new TreeSet<String>());
	/*package*/ volatile boolean started = false;
	private Pattern threadPattern   = Pattern.compile(DEFAULT_THREAD_PATTERN);
	private boolean throwExceptions = false;
	private long    bootstrapStart  = 0L;
	private long    bootstrapDelay  = 0L;
	private boolean afterBootstrap  = false;
	private Latency latencyFile     = Latency.MEDIUM;
	private Latency latencyNetwork  = Latency.LOW;
	private Latency latencyCPU      = Latency.MEDIUM;
	private boolean debug           = false;
	private Handler logHandler;

    public static String getPropertiesURL()
	{
		String url = System.getenv(KEY_AUDIT_FILENAME);
		if (url == null) url = DEFAULT_FILENAME;
		url = System.getProperty(KEY_AUDIT_FILENAME, url);
		return url.trim();
	}

	synchronized void startup()
	{
		if (!started)
		{
			started = true;
			bootstrapStart = System.currentTimeMillis();
			logOnly.commit();
			final String url = getPropertiesURL();
			new LoadParams(this, url).commit();
			logger.config("Start reactive audit with " + FileTools.homeFile(url));
            if (config.logger.isLoggable(Level.FINE))
            {
                config.logger.fine(String.format("%-30s = %s",KEY_THREAD_PATTERN,config.getThreadPattern()));
                config.logger.fine(String.format("%-30s = %s",KEY_THROW_EXCEPTIONS,config.isThrow()));
                config.logger.fine(String.format("%-30s = %s",KEY_BOOTSTRAP_DELAY,config.getBootstrapDelay()));
                config.logger.fine(String.format("%-30s = %s",KEY_FILE_LATENCY, config.getFileLatency()));
                config.logger.fine(String.format("%-30s = %s",KEY_NETWORK_LATENCY,config.getNetworkLatency()));
                config.logger.fine(String.format("%-30s = %s",KEY_CPU_LATENCY ,config.getCPULatency()));
            }
		}
	}


    synchronized void shutdown()
	{
        // Just shortly
        synchronized (LogManager.getLogManager())
        {
            String cr = System.getProperty("line.separator");
            long low = statLow.sum();
            long medium = statMedium.sum();
            long high = statHigh.sum();
            StringBuilder buf =
                    new StringBuilder(cr)
                            .append("\tTotal high  =").append(high).append(cr)
                            .append("\tTotal medium=").append(medium).append(cr)
                            .append("\tTotal low   =").append(low).append(cr)
                            .append("\tMax. concurrent threads=").append(statMaxThread.get())
                            .append(" (Number of node:").append(Runtime.getRuntime().availableProcessors()).append(")").append(cr)
                            .append("Shutdown audit");
            logger.config(buf.toString());
            if (logHandler != null) {
                logHandler.close();
            }
        }
	}

	void reset()
	{
		started = false;
		afterBootstrap = false;
		history.purge();
		stack.clear();
		historyThreadName.clear();
		startup();
	}

	public boolean isDebug()
	{
		return debug;
	}

	void setDebug(boolean debug)
	{
		this.debug = debug;
		logger.setLevel((debug) ? Level.FINE : DEFAULT_LOG_LEVEL);
		try
		{
			Field field = ReactiveAuditException.class.getDeclaredField("debug");
			field.setAccessible(true);
			field.set(null, debug);
		}
		catch (Exception e)
		{
			// Ignore
			logger.config("You detect a bug !" + System.getenv("line.separator") + e.getMessage());
		}
	}

	/**
	 * Increment the thread local variable to suppress audit for the current frame.
	 */
	public void incSuppress()
	{
		suppressAudit.set(suppressAudit.get() + 1);
	}

	/**
	 * Decrement the thread local variable to suppress audit for the current frame.
	 */
	public void decSuppress()
	{
		suppressAudit.set(suppressAudit.get() - 1);
	}

	/**
	 * Return the current suppress counter. For unit test only.
	 *
	 * @return The local variable.
	 */
	int getSuppress()
	{
		return suppressAudit.get();
	}

	/**
	 * Check if the current thread name match the pattern.
	 *
	 * @param name The thread name.
	 * @return <code>true</code> if match.
	 */
	boolean isThreadNameMatch(String name)
	{
		if (name == null) return false;
		if (threadPattern == null)
		{
			return false;
		}
		if (historyThreadName.add(name))
		{
			int now = ManagementFactory.getThreadMXBean().getThreadCount();
			int old;
			for (; ; )
			{
				old = statMaxThread.get();
				if (now > old)
				{
					if (statMaxThread.compareAndSet(old, now))
						break;
				}
				else
					break;
				Thread.yield();
			}
			if (debug) logger.fine("Detect thread name \"" + name + "\"");
		}
		return threadPattern.matcher(name).matches();
	}

	/**
	 * Ask if supress the audit for the current frame.
	 *
	 * @return <code>true</code> if refuse the audit now.
	 */
	boolean isSuppressAudit()
	{
		return suppressAudit.get() != 0;
	}

	boolean isAfterStartupDelay()
	{
		if (afterBootstrap) return true;
		if ((System.currentTimeMillis() - bootstrapStart) > (bootstrapDelay*1000))
		{
			afterBootstrap = true;
			return true;
		}
		return false;
	}

	/**
	 * Return the current bootstrap delay before start the audit.
	 *
	 * @return The delay in MS after the startup.
	 */
	public long getBootstrapDelay()
	{
		return bootstrapDelay;
	}

	public Latency getFileLatency()
	{
		return latencyFile;
	}

	public Latency getNetworkLatency()
	{
		return latencyNetwork;
	}

	public Latency getCPULatency()
	{
		return latencyCPU;
	}

	/**
	 * Throw an exception if detect an error ?
	 *
	 * @return the current status.
	 */
	public boolean isThrow()
	{
		//logger.config("Throw exception");
		return throwExceptions;
	}

	/**
	 * Return the current thread pattern.
	 *
	 * @return The current thread pattern.
	 */
	public String getThreadPattern()
	{
		return threadPattern.toString();
	}

	/**
	 * @return Return a transaction with the current value.
	 */
	private Transaction current()
	{
		return new Transaction()
				.throwExceptions(throwExceptions)
				.threadPattern(threadPattern.toString())
				.bootStrapDelay(bootstrapDelay);
	}

	/**
	 * Push the current values.
	 */
	protected void push()
	{
		stack.push(current());
	}

	/**
	 * Apply the last values.
	 */
	protected void pop()
	{
		stack.pop().commit();
	}

	/**
	 * Begin a transaction to update the parameters.
	 * Call commit() to apply.
	 *
	 * @return The transaction.
	 */
	public Transaction begin()
	{
		return new Transaction();
	}

	@SuppressWarnings({"ChainOfInstanceofChecks", "InstanceofInterfaces"})
	public void logIfNew(Latency latencyLevel, ReactiveAuditException e)
	{
		Latency baseLatency = null;
		if (e instanceof FileReactiveAuditException)
			baseLatency = latencyFile;
		else if (e instanceof NetworkReactiveAuditException)
			baseLatency = latencyNetwork;
		else if (e instanceof CPUReactiveAuditException)
			baseLatency = latencyCPU;
		if (baseLatency == null) return;
		if (e.getLatency().ordinal() < baseLatency.ordinal())
			return;

		if (!history.isAlreadyLogged(e.getStackTrace()))
		{
			Level level;
			switch (latencyLevel)
			{
				case LOW:
					level = Level.INFO;
					statLow.add(1);
					break;
				case MEDIUM:
					level = Level.WARNING;
					statMedium.add(1);
					break;
				default:
					level = Level.SEVERE;
					statHigh.add(1);
					break;
			}

			logger.log(level, e.getMessage(), e);
		}
	}

	public void debug(Object s)
	{
		logger.fine(String.valueOf(s));
	}

	public void dumpTarget(Object obj)
	{
		debug("DUMP TARGET: " + obj);
		Class<?> cl = obj.getClass();
		debug(cl.getName() + " extends " + cl.getSuperclass());
		for (Class<?> c : cl.getInterfaces())
		{
			ReactiveAudit.config.debug("implements " + c);
		}

	}

    /*
	public class FakeLogger //extends Logger
	{
		public Handler[] getHandlers()
		{
			return new Handler[0];
		}

		public void addHandler(Handler handler) throws SecurityException
		{
			//super.addHandler(handler);
		}

		public void removeHandler(Handler handler) throws SecurityException
		{
			//super.removeHandler(handler);
		}

		public void setUseParentHandlers(boolean b)
		{
		}

		public Logger getParent()
		{
			return null;
		}

		public void log(Level level, String msg)
		{

		}

		public void info(String msg)
		{
			System.err.println("INFO " + msg);
		}

		public void config(String msg)
		{
			System.err.println("CONFIG " + msg);

		}

		public void fine(String msg)
		{
			System.err.println("FINE " + msg);
		}

		public void finest(String msg)
		{
			System.err.println("FINEST " + msg);
		}

		public void severe(String msg)
		{
			System.err.println("SEVERE " + msg);
		}

		public void warning(String msg)
		{
			System.err.println("WARNING " + msg);
		}

		public void log(Level level, String msg, Object param1)
		{
			fine(msg);
		}

		public Level getLevel()
		{
			return Level.FINE;
		}

		public void setLevel(Level level)
		{
		}
//		protected EmptyLogger(String name, String resourceBundleName)
//		{
//			super(name, resourceBundleName);
//		}
	}
	*/

	/**
	 * A current transaction to modify the parameters.
	 */
	public class Transaction
	{
		private final List<Runnable> commands = new ArrayList<Runnable>();
		private boolean sealed;

		private void add(Runnable cmd)
				throws IllegalArgumentException
		{
			if (sealed) throw new IllegalArgumentException("Sealed");
			commands.add(cmd);
		}

		/**
		 * Seal the transaction.
		 *
		 * @return this
		 */
		/*package*/ Transaction seal()
		{
			sealed = true;
			return this;
		}

		/**
		 * Ask to throw an exception if detect an error.
		 * May be apply after the commit().
		 *
		 * @param onOff true or fall
		 * @return The current transaction.
		 */
		public Transaction throwExceptions(final boolean onOff)
		{
			add(new Runnable()
			{
				@Override
				public void run()
				{
					throwExceptions = onOff;
				}
			});
			return this;
		}

		public Transaction latencyFile(String level)
		{
			final Latency latency = (level.length()) == 0 ? null : Latency.valueOf(level.toUpperCase());
			add(new Runnable()
			{
				@Override
				public void run()
				{
					latencyFile = latency;
				}
			});
			return this;
		}

		public Transaction latencyNetwork(String level)
		{
			final Latency latency = (level.length()) == 0 ? null : Latency.valueOf(level.toUpperCase());
			add(new Runnable()
			{
				@Override
				public void run()
				{
					latencyNetwork = latency;
				}
			});
			return this;
		}

		public Transaction latencyCPU(String level)
		{
			final Latency latency = (level.length()) == 0 ? null : Latency.valueOf(level.toUpperCase());
			add(new Runnable()
			{
				@Override
				public void run()
				{
					latencyCPU = latency;
				}
			});
			return this;
		}

		/**
		 * Select the log output.
		 * May be apply after the commit().
		 *
		 * @param output "console" or file name.
		 * @param format The string format.
		 * @param size   The size before rolling the files.
		 * @return The current transaction.
		 */
		public Transaction logOutput(final String output, final String format, final String size)
		{
			try
			{
				final int isize = Integer.parseInt(size);
                final Handler handler = ("console".equalsIgnoreCase(output))
                        ? new ConsoleHandler()
                        : new FileHandler(output, isize,
                        (isize == 0) ? 1 : 5, false);
                if (output.endsWith(".xml"))
                    handler.setFormatter(new java.util.logging.XMLFormatter());
                else
                {
                    handler.setFormatter(new AuditLogFormat(format));
                }
				handler.setLevel(DEFAULT_LOG_LEVEL);
				add(new Runnable()
				{
					@Override
					public void run()
					{
						for (Handler h : logger.getHandlers())
						{
							logger.removeHandler(h);
						}
						logHandler = handler;
						logger.addHandler(handler);
						logger.setUseParentHandlers(false);
						final Level level=(debug) ? Level.FINE : DEFAULT_LOG_LEVEL;
						logger.setLevel(level);
						handler.setLevel(level);
					}
				});
			}
			catch (IOException e)
			{
				logger.config("Log file error (" + e.getMessage() + ")");
			}
			catch (InvalidPathException e)
			{
				logger.config("Log file error (" + e.getMessage() + ")");
			}
			return this;
		}

		/**
		 * Ask a specific pattern for detect the reactive thread.
		 * May be apply after the commit().
		 *
		 * @param pattern The regexp pattern.
		 * @return The current transaction.
		 */
		public Transaction threadPattern(final String pattern)
		{
			add(new Runnable()
			{
				@Override
				public void run()
				{
					threadPattern = Pattern.compile(pattern);
				}
			});
			return this;
		}

		/**
		 * Ask a specific boot strap delay before start the audit.
		 * May be apply after the commit().
		 *
		 * @param delay The new delay.
		 * @return The current transaction.
		 */
		public Transaction bootStrapDelay(final long delay)
		{
			add(new Runnable()
			{
				@Override
				public void run()
				{
					bootstrapDelay = delay;
				}
			});
			return this;
		}

		/**
		 * Set the debug mode.
		 * With debug mode, the exception was not clean with the aspect.
		 * May be apply after the commit().
		 *
		 * @param aDebug The debug mode.
		 * @return The current transaction.
		 */
		public Transaction debug(final boolean aDebug)
		{
			add(new Runnable()
			{
				@Override
				public void run()
				{
					setDebug(aDebug);
				}
			});
			return this;
		}

		/**
		 * Apply all the modifications describe in the transcation.
		 * Can be apply many times.
		 */
		public synchronized void commit()
		{
			for (Runnable r : commands) r.run();
			statLow.reset();
			statMedium.reset();
			statLow.reset();
		}
	}
}
