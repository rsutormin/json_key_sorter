package us.kbase.common.taskqueue;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import us.kbase.common.utils.DbConn;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class TaskQueue {
	private DbConn conn;
	private Map<Class<?>, TaskRunner<?>> runners = new HashMap<Class<?>, TaskRunner<?>>();
	private Map<String, Task> taskMap = new HashMap<String, Task>();
	private LinkedList<Task> taskQueue = new LinkedList<Task>();
	private Thread[] allThreads;
	private boolean needToStop = false;
	private final Object idleMonitor = new Object();
	private final TaskQueueConfig config;
	    
    private static final int MAX_ERROR_MESSAGE_LEN = 190;
    public static final String DERBY_DB_NAME = "GenomeCmpDb";
    public static final String QUEUE_TABLE_NAME = "task_queue";
	
	public TaskQueue(TaskQueueConfig config) throws ClassNotFoundException, SQLException {
		this.config = config;
		conn = getDbConnection(config.getQueueDbDir());
		if (!conn.checkTable(QUEUE_TABLE_NAME)) {
			conn.exec("create table " + QUEUE_TABLE_NAME + " (" +
					"jobid varchar(100) primary key," +
					"type varchar(100)," +
					"params varchar(1000)," +
					"auth varchar(1000)," +
					"outref varchar(1000)" +
					")");
		}
		allThreads = new Thread[config.getThreadCount()];
		for (int i = 0; i < allThreads.length; i++) {
			allThreads[i] = startNewThread(i);
		}
		checkForUnfinishedTasks();
	}
	
	public TaskQueue registerRunner(TaskRunner<?> runner) {
		runner.init(config, config.getAllConfigProps());
		runners.put(runner.getInputDataType(), runner);
		return this;
	}

	public static DbConn getDbConnection(File dbParentDir) throws ClassNotFoundException, SQLException {
		Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
		File dbDir = new File(dbParentDir, DERBY_DB_NAME);
		String url = "jdbc:derby:" + dbDir.getParent() + "/" + dbDir.getName();
		if (!dbDir.exists())
			url += ";create=true";
		return new DbConn(DriverManager.getConnection(url));
	}
	
	private void checkForUnfinishedTasks() throws SQLException {
		List<Task> tasks = conn.collect("select jobid,type,params,auth,outref from " + 
				QUEUE_TABLE_NAME, new DbConn.SqlLoader<Task>() {
			@Override
			public Task collectRow(ResultSet rs) throws SQLException {
				try {
					Class<?> type = Class.forName(rs.getString("type"));
					String paramsJson = rs.getString("params");
					Object params = new ObjectMapper().readValue(paramsJson, type);
					return new Task(rs.getString("jobid"), params, rs.getString("auth"),
							rs.getString("outref"));
				} catch (ClassNotFoundException e) {
					throw new IllegalStateException(e);
				} catch (IOException e) {
					throw new IllegalStateException(e);
				}
			}
		});
		for (Task task : tasks)
			addTask(task);
		if (tasks.size() > 0) {
			synchronized (idleMonitor) {
				idleMonitor.notifyAll();
			}
		}
	}
	
	@SuppressWarnings("unchecked")
	public <T> String addTask(T params, String authToken) throws Exception {
		TaskRunner<T> runner = (TaskRunner<T>)runners.get(params.getClass());
		if (runner == null)
			throw new IllegalStateException("Task data type is not supported: " + params.getClass().getName());
		String outRef = runner.getOutRef(params);
		return addTask(params, authToken, runner.getTaskDescription(), outRef);
	}

	public String addTaskForTest(Runnable params, String authToken) throws Exception {
		return addTask(params, authToken, "descr", "out");
	}
	
	private synchronized String addTask(Object params, String authToken, String description, String outRef) throws Exception {
		String jobId = createQueuedTaskJob(description, authToken);
		Task task = new Task(jobId, params, authToken, outRef);
		addTask(task);
		storeTaskInDb(task);
		synchronized (idleMonitor) {
			idleMonitor.notify();
		}
		return jobId;
	}

	private synchronized void addTask(Task task) {
		taskQueue.addLast(task);
		taskMap.put(task.getJobId(), task);
	}
	
	private void storeTaskInDb(Task task) throws JsonProcessingException, SQLException {
		String type = task.getParams().getClass().getName();
		String params = new ObjectMapper().writeValueAsString(task.getParams());
		conn.exec("insert into " + QUEUE_TABLE_NAME + " (jobid,type,params,auth,outref) values (?,?,?,?,?)", 
				task.getJobId(), type, params, task.getAuthToken(), task.getOutRef());
	}
	
	private void deleteTaskFromDb(String jobId) throws SQLException {
		conn.exec("delete from " + QUEUE_TABLE_NAME + " where jobid=?", jobId);
	}
	
	private synchronized void removeTask(Task task) {
		try {
			deleteTaskFromDb(task.getJobId());
		} catch (Throwable ex) {
			ex.printStackTrace();
		}
		taskMap.remove(task.getJobId());
	}
	
	public synchronized Task getTask(String jobId) {
		return taskMap.get(jobId);
	}
	
	private synchronized Task gainNewTask() {
		if (taskQueue.size() > 0) {
			Task ret = taskQueue.removeFirst();
			return ret;
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private void runTask(Task task) {
		String token = task.getAuthToken();
		try {
			changeTaskStateIntoRunning(task, token);
			Object params = task.getParams();
			TaskRunner<Object> runner = (TaskRunner<Object>)runners.get(params.getClass());
			if (runner == null)
				throw new IllegalStateException("Task data type is not supported: " + params.getClass().getName());
			runner.run(token, params, task.getJobId(), task.getOutRef());
			completeTaskState(task, token, null, null);
		} catch (Throwable e) {
			try {
				StringWriter sw = new StringWriter();
				PrintWriter pw = new PrintWriter(sw);
				e.printStackTrace(pw);
				pw.close();
				String errMsg = null;
				if (e.getMessage() == null) {
					errMsg = e.getClass().getSimpleName();
				} else {
					errMsg = "Error: " + e.getMessage();
				}
				if (errMsg.length() > MAX_ERROR_MESSAGE_LEN)
					errMsg = errMsg.substring(0, MAX_ERROR_MESSAGE_LEN - 3) + "...";
				completeTaskState(task, token, errMsg, sw.toString());
			} catch (Throwable ex) {
				ex.printStackTrace();
			}
		}
	}

	private String createQueuedTaskJob(String description, String token) throws Exception {
		return config.getJobStatuses().createAndStartJob(token, "queued", description, 
				"none", null);
	}

	private void changeTaskStateIntoRunning(Task task, String token) throws Exception {
		config.getJobStatuses().updateJob(task.getJobId(), token, "running", null);
	}

	private void completeTaskState(Task task, String token, String errorMessage, String errorStacktrace) throws Exception {
		if (errorMessage == null) {
			config.getJobStatuses().completeJob(task.getJobId(), token, "done", null, 
					config.getWsUrl(), task.getOutRef());
		} else {
			config.getJobStatuses().completeJob(task.getJobId(), token, errorMessage, 
					errorStacktrace, null, null); 
		}
	}
	
	public void stopAllThreads() {
		needToStop = true;
		for (Thread t : allThreads)
			t.interrupt();
	}
	
	private Thread startNewThread(final int num) {
		Thread ret = new Thread(
				new Runnable() {
					@Override
					public void run() {
						while (!needToStop) {
							Task task = gainNewTask();
							if (task != null) {
								runTask(task);
								removeTask(task);
							} else {
								long ms = 55 * 1000 + (int)(10 * 1000 * Math.random());
								synchronized (idleMonitor) {
									try {
										idleMonitor.wait(ms);
									} catch (InterruptedException e) {
										if (!needToStop)
											e.printStackTrace();
									}
								}
							}
						}
						System.out.println("Task thread " + (num + 1) + " was stoped");
					}
				},"Task thread " + (num + 1));
		ret.start();
		return ret;
	}
}