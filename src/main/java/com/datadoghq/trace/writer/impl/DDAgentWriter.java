package com.datadoghq.trace.writer.impl;

import com.datadoghq.trace.Writer;
import com.datadoghq.trace.impl.DDSpan;
import io.opentracing.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;

/**
 * This writer write provided traces to the a DD agent which is most of time located on the same host.
 * 
 * It handles writes asynchronuously so the calling threads are automatically released. However, if too much spans are collected
 * the writers can reach a state where it is forced to drop incoming spans.
 */
public class DDAgentWriter implements Writer {

	protected static final Logger logger = LoggerFactory.getLogger(DDAgentWriter.class.getName());
	
	/**
	 * Default location of the DD agent
	 */
	protected static final String DEFAULT_HOSTNAME = "localhost";
	protected static final int DEFAULT_PORT = 8126;
	
	/**
	 * Maximum number of spans kept in memory
	 */
	protected static final int DEFAULT_MAX_SPANS = 1000;
	
	/**
	 * Maximum number of traces sent to the DD agent API at once
	 */
	protected static final int DEFAULT_BATCH_SIZE = 10;

	/**
	 * Used to ensure that we don't keep too many spans (while the blocking queue collect traces...)
	 */
	private final Semaphore tokens;
	
	/**
	 * In memory collection of traces waiting for departure
	 */
	protected final BlockingQueue<List<DDSpan>> traces;
	
	/**
	 * Async worker that posts the spans to the DD agent
	 */
	protected final Thread asyncWriterThread;

	/**
	 * The DD agent api
	 */
	protected final DDApi api;

	public DDAgentWriter() {
		this(new DDApi(DEFAULT_HOSTNAME, DEFAULT_PORT));		
	}
	
	public DDAgentWriter(DDApi api) {
		super();
		this.api = api;
		
		tokens = new Semaphore(DEFAULT_MAX_SPANS);
		traces = new ArrayBlockingQueue<List<DDSpan>>(DEFAULT_MAX_SPANS);

		asyncWriterThread = new Thread(new SpansSendingTask(), "dd.DDAgentWriter-SpansSendingTask");
		asyncWriterThread.setDaemon(true);
		asyncWriterThread.start();
	}

	/* (non-Javadoc)
	 * @see com.datadoghq.trace.Writer#write(java.util.List)
	 */
	public void write(List<DDSpan> trace) {
		//Try to add a new span in the queue
		boolean proceed = tokens.tryAcquire(trace.size());

		if(proceed){
			traces.add(trace);
		}else{
			logger.warn("Cannot add a trace of "+trace.size()+" as the async queue is full. Queue max size:"+DEFAULT_MAX_SPANS);
		}
	}

	/* (non-Javadoc)
	 * @see com.datadoghq.trace.Writer#close()
	 */
	public void close() {
		asyncWriterThread.interrupt();
		try {
			asyncWriterThread.join();
		} catch (InterruptedException e) {
			logger.info("Writer properly closed and async writer interrupted.");
		}
	}

	/**
	 * Infinite tasks blocking until some spans come in the blocking queue.
	 */
	protected class SpansSendingTask implements Runnable {
		
		public void run() {
			while (true) {
				try {
					List<List<DDSpan>> payload = new ArrayList<List<DDSpan>>();
					
					//WAIT until a new span comes
					List<DDSpan> l = DDAgentWriter.this.traces.take();
					payload.add(l);
					
					//Drain all spans up to a certain batch suze
					traces.drainTo(payload, DEFAULT_BATCH_SIZE);

					//SEND the payload to the agent
					logger.debug("Async writer about to write "+payload.size()+" traces.");
					api.sendTraces(payload);

					//Compute the number of spans sent
					int spansCount = 0;
					for(List<DDSpan> trace:payload){
						spansCount+=trace.size();
					}
					logger.debug("Async writer just sent "+spansCount+" spans through "+payload.size()+" traces");

					//Release the tokens
					tokens.release(spansCount);
				} catch (InterruptedException e) {
					logger.info("Async writer interrupted.");

					//The thread was interrupted, we break the LOOP
					break;
				}
			}
		}
	}
}
