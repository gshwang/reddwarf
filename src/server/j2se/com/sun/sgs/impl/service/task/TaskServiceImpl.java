/*
 * Copyright 2007-2008 Sun Microsystems, Inc.
 *
 * This file is part of Project Darkstar Server.
 *
 * Project Darkstar Server is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation and
 * distributed hereunder to you.
 *
 * Project Darkstar Server is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.sun.sgs.impl.service.task;

import com.sun.sgs.app.ExceptionRetryStatus;
import com.sun.sgs.app.ManagedObject;
import com.sun.sgs.app.ManagedReference;
import com.sun.sgs.app.NameNotBoundException;
import com.sun.sgs.app.ObjectNotFoundException;
import com.sun.sgs.app.PeriodicTaskHandle;
import com.sun.sgs.app.Task;
import com.sun.sgs.app.TaskRejectedException;

import com.sun.sgs.app.util.ScalableHashSet;

import com.sun.sgs.auth.Identity;

import com.sun.sgs.impl.sharedutil.LoggerWrapper;
import com.sun.sgs.impl.sharedutil.PropertiesWrapper;

import com.sun.sgs.impl.util.TransactionContext;
import com.sun.sgs.impl.util.TransactionContextFactory;

import com.sun.sgs.kernel.ComponentRegistry;
import com.sun.sgs.kernel.KernelRunnable;
import com.sun.sgs.kernel.Priority;
import com.sun.sgs.kernel.RecurringTaskHandle;
import com.sun.sgs.kernel.TaskReservation;
import com.sun.sgs.kernel.TaskScheduler;

import com.sun.sgs.profile.ProfileConsumer;
import com.sun.sgs.profile.ProfileOperation;
import com.sun.sgs.profile.ProfileProducer;
import com.sun.sgs.profile.ProfileRegistrar;

import com.sun.sgs.service.DataService;
import com.sun.sgs.service.Node;
import com.sun.sgs.service.NodeMappingListener;
import com.sun.sgs.service.NodeMappingService;
import com.sun.sgs.service.RecoveryCompleteFuture;
import com.sun.sgs.service.RecoveryListener;
import com.sun.sgs.service.TaskService;
import com.sun.sgs.service.Transaction;
import com.sun.sgs.service.TransactionProxy;
import com.sun.sgs.service.TransactionRunner;
import com.sun.sgs.service.UnknownIdentityException;
import com.sun.sgs.service.WatchdogService;

import java.io.Serializable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import java.util.concurrent.ConcurrentHashMap;

import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * This is an implementation of {@code TaskService} that works on a
 * single node and across multiple nodes. It handles persisting tasks and
 * keeping track of which tasks have not yet run to completion, so that in
 * the event of a system failure the tasks can be run on re-start.
 * <p>
 * Durable tasks that have not yet run are persisted as instances of
 * {@code PendingTask}, indexed by the owning identity. When a given identity
 * is mapped to the local node, all tasks associated with that identity are
 * started running on the local node. As long as an identity still has pending
 * tasks scheduled to run locally, that identity is marked as active.
 * <p>
 * When an identity is moved from the local node to a new node, then all
 * recurring tasks for that identity are cancelled, and all tasks for that
 * identity are re-scheduled on the identity's new node. When an
 * already-scheduled, persisted task tries to run on the old node, that task
 * is dropped since it is already scheduled to run on the new node. After an
 * identity has been moved, any subsiquent attempts to schedule durable tasks
 * on behalf of that identity on the old node will result in the tasks being
 * scheduled to run on the new node. This is called task handoff.
 * <p>
 * Task handoff between nodes is done by noting the task in a node-specific
 * entry in the data store. Each node will periodically query this entry to
 * see if any tasks have been handed off. The default time in milliseconds
 * for this period is {@code HANDOFF_PERIOD_DEFAULT}. The period may be
 * changed using the property {@code HANDOFF_PERIOD_PROPERTY}. This checking
 * will be delayed on node startup to give the system a chance to finish
 * initializing. The default length of time in milliseconds for this delay is
 * {@code HANDOFF_START_DEFAULT}. The delay may be changed using the property
 * {@code HANDOFF_START_PROPERTY}.
 * <p>
 * When the final task for an identity completes, or an initial task for an
 * identity is scheduled, the status of that identity as reported by this
 * service changes. Rather than immediately reporting this status change,
 * however, a delay is taken to see if the status is about to change back to
 * its previous state. This helps avoid voting too frequently. The default
 * time in milliseconds for delaying this vote is {@code VOTE_DELAY_DEFAULT}.
 * The delay may be changed using the property {@code VOTE_DELAY_PROPERTY}.
 */
public class TaskServiceImpl implements ProfileProducer, TaskService,
                                        NodeMappingListener, RecoveryListener {

    /**
     * The identifier used for this {@code Service}.
     */
    public static final String NAME = TaskServiceImpl.class.getName();

    // logger for this class
    private static final LoggerWrapper logger =
        new LoggerWrapper(Logger.getLogger(NAME));

    /**
     * The name prefix used to bind all service-level objects associated
     * with this service.
     */
    public static final String DS_PREFIX = NAME + ".";

    // the namespace where pending tasks are kept (this name is always
    // followed by the pending task's identity)
    private static final String DS_PENDING_SPACE = DS_PREFIX + "Pending.";

    // the transient set of identities known to be active on the current node,
    // and how many tasks are pending for that identity
    private HashMap<Identity,Integer> activeIdentityMap;

    // the transient set of identies thought to be mapped to this node
    private HashSet<Identity> mappedIdentitySet;

    // a timer used to delay status votes
    private final Timer statusUpdateTimer;

    /** The property key to set the delay in milliseconds for status votes. */
    public static final String VOTE_DELAY_PROPERTY =
        NAME + ".vote.delay";

    /** The default delay in milliseconds for status votes. */
    public static final long VOTE_DELAY_DEFAULT = 5000L;

    // the length of time to delay status votes
    private final long voteDelay;

    // a map of any pending status change timer tasks
    private ConcurrentHashMap<Identity,TimerTask> statusTaskMap;

    // the base namespace where all tasks are handed off (this name is always
    // followed by the recipient node's identifier)
    private static final String DS_HANDOFF_SPACE = DS_PREFIX + "Handoff.";

    // the local node's hand-off namespace
    private final String localHandoffSpace;

    /** The property key to set the delay before hand-off checking starts. */
    public static final String HANDOFF_START_PROPERTY =
        NAME + ".handoff.start";

    /** The default delay in millseconds before hand-off checking starts. */
    public static final long HANDOFF_START_DEFAULT = 2500L;

    // the actual amount of time to wait before hand-off checking starts
    private final long handoffStart;

    /** The property key to set how long to wait between hand-off checks. */
    public static final String HANDOFF_PERIOD_PROPERTY =
        NAME + ".handoff.period";

    /** The default length of time in milliseconds to wait between hand-off
        checks. */
    public static final long HANDOFF_PERIOD_DEFAULT = 500L;

    // the actual amount of time to wait between hand-off checks
    private final long handoffPeriod;

    // a handle to the periodic hand-off task
    private RecurringTaskHandle handoffTaskHandle = null;

    // the internal value used to represent a task with no delay
    private static final long START_NOW = 0;

    // the internal value used to represent a task that does not repeat
    static final long PERIOD_NONE = -1;

    // the system's task scheduler, where tasks actually run
    private final TaskScheduler taskScheduler;

    // a proxy providing access to the transaction state
    private static TransactionProxy transactionProxy = null;

    // the owning application context used for re-starting tasks
    private final Identity appOwner;

    // the identifier for the local node
    private final long nodeId;

    // the data service used in the same context
    private final DataService dataService;

    // the mapping service used in the same context
    private final NodeMappingService nodeMappingService;

    // the factory used to manage transaction state
    private final TransactionContextFactory<TxnState> ctxFactory;

    // the transient map for all recurring tasks' handles
    private ConcurrentHashMap<String,RecurringDetail> recurringMap;

    // the transient map for all recurring handles based on identity
    private HashMap<Identity,Set<RecurringTaskHandle>> identityRecurringMap;

    // a flag noting whether this service has shutdown
    private volatile boolean isShutdown = false;

    // a local copy of the default priority, which is used in almost all
    // cases for tasks submitted by this service
    private static Priority defaultPriority = Priority.getDefaultPriority();

    // the profiled operations
    private ProfileOperation scheduleNDTaskOp = null;
    private ProfileOperation scheduleNDTaskDelayedOp = null;
    private ProfileOperation scheduleNDTaskPrioritizedOp = null;

    /**
     * Creates an instance of {@code TaskServiceImpl}. See the class javadoc
     * for applicable properties.
     *
     * @param properties application properties
     * @param systemRegistry the registry of system components
     * @param transactionProxy the system's {@code TransactionProxy}
     *
     * @throws Exception if the service cannot be created
     */
    public TaskServiceImpl(Properties properties,
                           ComponentRegistry systemRegistry,
                           TransactionProxy transactionProxy)
        throws Exception
    {
        if (properties == null)
            throw new NullPointerException("Null properties not allowed");
        if (systemRegistry == null)
            throw new NullPointerException("Null registry not allowed");
        if (transactionProxy == null)
            throw new NullPointerException("Null proxy not allowed");

        logger.log(Level.CONFIG, "creating TaskServiceImpl");

        // create the transient local collections
        activeIdentityMap = new HashMap<Identity,Integer>();
        mappedIdentitySet = new HashSet<Identity>();
        statusTaskMap = new ConcurrentHashMap<Identity,TimerTask>();
        recurringMap = new ConcurrentHashMap<String,RecurringDetail>();
        identityRecurringMap =
            new HashMap<Identity,Set<RecurringTaskHandle>>();

        // create the factory for managing transaction context
        ctxFactory = new TransactionContextFactoryImpl(transactionProxy);

        // keep a reference to the system components...
        this.transactionProxy = transactionProxy;
        taskScheduler = systemRegistry.getComponent(TaskScheduler.class);

        // ...and to the other Services that are needed
        dataService = transactionProxy.getService(DataService.class);
        nodeMappingService =
            transactionProxy.getService(NodeMappingService.class);

        appOwner = transactionProxy.getCurrentOwner();
        
        // note that the application is always active locally, so there's
        // no chance of voting the application as inactive
        activeIdentityMap.put(appOwner, 1);

        // register for identity mapping updates
        nodeMappingService.addNodeMappingListener(this);

        // get the current node id for the hand-off namespace, and register
        // for recovery notices to manage cleanup of hand-off bindings
        WatchdogService watchdogService =
            transactionProxy.getService(WatchdogService.class);
        nodeId = watchdogService.getLocalNodeId();
        localHandoffSpace = DS_HANDOFF_SPACE + nodeId;
        watchdogService.addRecoveryListener(this);

        // get the start delay and the length of time between hand-off checks
        PropertiesWrapper wrappedProps = new PropertiesWrapper(properties);
        handoffStart = wrappedProps.getLongProperty(HANDOFF_START_PROPERTY,
                                                    HANDOFF_START_DEFAULT);
        if (handoffStart < 0)
            throw new IllegalStateException("Handoff Start property must " +
                                            "be non-negative");
        handoffPeriod = wrappedProps.getLongProperty(HANDOFF_PERIOD_PROPERTY,
                                                     HANDOFF_PERIOD_DEFAULT);
        if (handoffPeriod < 0)
            throw new IllegalStateException("Handoff Period property must " +
                                            "be non-negative");

        // finally, create a timer for delaying the status votes and get
        // the delay used in submitting status votes
        statusUpdateTimer = new Timer("TaskServiceImpl Status Vote Timer");
        voteDelay = wrappedProps.getLongProperty(VOTE_DELAY_PROPERTY,
                                                 VOTE_DELAY_DEFAULT);
        if (voteDelay < 0)
            throw new IllegalStateException("Vote Delay property must " +
                                            "be non-negative");
    }

    /**
     * {@inheritDoc}
     */
    public String getName() {
        return NAME;
    }

    /**
     * {@inheritDoc}
     */
    public void ready() {
        if (isShutdown)
            throw new IllegalStateException("Service is shutdown");

        logger.log(Level.CONFIG, "readying TaskService");

        // bind the node-local hand-off set, noting that there's a (very
        // small) chance that another node may have already tried to hand-off
        // to us, in which case the set will already exist
        try {
            taskScheduler.runTask(new TransactionRunner(new KernelRunnable() {
                    public String getBaseTaskType() {
                        return NAME + ".HandoffBindingRunner";
                    }
                    public void run() throws Exception {
                        try {
                            dataService.getServiceBinding(localHandoffSpace,
                                                          StringHashSet.class);
                        } catch (NameNotBoundException nnbe) {
                            dataService.setServiceBinding(localHandoffSpace,
                                                          new StringHashSet());
                        }
                    }
                }), appOwner, true);
        } catch (Exception e) {
            throw new AssertionError("Failed to setup node-local sets");
        }

        // assert that the application identity is active, so that there
        // is always a mapping somewhere for these tasks
        // NOTE: in our current system, there may be a large number of
        // tasks owned by the application (e.g., any tasks started
        // during the application's initialize() method), but hopefully
        // this will change when we add APIs for creating identities
        nodeMappingService.assignNode(getClass(), appOwner);

        // kick-off a periodic hand-off task, but delay a little while so
        // that the system has a chance to finish setup
        handoffTaskHandle = taskScheduler.
            scheduleRecurringTask(new TransactionRunner(new HandoffRunner()),
                                  transactionProxy.getCurrentOwner(),
                                  System.currentTimeMillis() + handoffStart,
                                  handoffPeriod);
        handoffTaskHandle.start();

        logger.log(Level.CONFIG, "TaskService is ready");
    }

    /**
     * {@inheritDoc}
     */
    public boolean shutdown() {
        synchronized (this) {
            if (isShutdown)
                throw new IllegalStateException("Service is shutdown");
            isShutdown = true;
        }

        // stop the handoff and status processing tasks
        handoffTaskHandle.cancel();
        statusUpdateTimer.cancel();

        return true;
    }

    /**
     * {@inheritDoc}
     */
    public void recover(Node node, RecoveryCompleteFuture future) {
        final long failedNodeId =  node.getId();
        final String handoffSpace = DS_HANDOFF_SPACE + failedNodeId;

        // remove the handoff set and binding for the failed node
        try {
            taskScheduler.runTransactionalTask(new KernelRunnable() {
                    public String getBaseTaskType() {
                        return NAME + ".HandoffCleanupRunner";
                    }
                    public void run() throws Exception {
                        StringHashSet set = null;
                        try {    
                            set = dataService.
                                getServiceBinding(handoffSpace,
                                                  StringHashSet.class);
                        } catch (NameNotBoundException nnbe) {
                            // this only happens when this recover method
                            // is called more than once, and just means that
                            // this cleanup has already happened, so we can
                            // quietly ignore this case
                            return;
                        }
                        dataService.removeObject(set);
                        dataService.removeServiceBinding(handoffSpace);
                        if (logger.isLoggable(Level.INFO))
                            logger.log(Level.INFO, "Cleaned up handoff set " +
                                       "for failed node: " + failedNodeId);
                   }
                }, appOwner);
        } catch (Exception e) {
            if (logger.isLoggable(Level.WARNING))
                logger.logThrow(Level.WARNING, e, "Failed to cleanup handoff " +
                                "set for failed node: " + failedNodeId);
        }

        future.done();
    }

    /**
     * {@inheritDoc}
     */
    public void setProfileRegistrar(ProfileRegistrar profileRegistrar) {
        ProfileConsumer consumer =
            profileRegistrar.registerProfileProducer(this);

        if (consumer != null) {
            scheduleNDTaskOp =
                consumer.registerOperation("scheduleNonDurableTask");
            scheduleNDTaskDelayedOp =
                consumer.registerOperation("scheduleNonDurableTaskDelayed");
            scheduleNDTaskPrioritizedOp =
                consumer.registerOperation("scheduleNonDurableTaskPrioritized");
        }
    }

    /**
     * {@inheritDoc}
     */
    public void scheduleTask(Task task) {
        scheduleSingleTask(task, START_NOW);
    }

    /**
     * {@inheritDoc}
     */
    public void scheduleTask(Task task, long delay) {
        long startTime = System.currentTimeMillis() + delay;

        if (delay < 0)
            throw new IllegalArgumentException("Delay must not be negative");

        scheduleSingleTask(task, startTime);
    }

    /** Private helper for common scheduling code. */
    private void scheduleSingleTask(Task task, long startTime) {
        if (task == null)
            throw new NullPointerException("Task must not be null");
        if (isShutdown)
            throw new IllegalStateException("Service is shutdown");

        // persist the task regardless of where it will ultimately run
        Identity owner = transactionProxy.getCurrentOwner();
        TaskRunner runner = getRunner(task, owner, startTime, PERIOD_NONE);

        // check where the owner is active to get the task running
        if (! isMappedLocally(owner)) {
            if (handoffTask(runner.getObjName(), owner))
                return;
            runner.markIgnoreIsLocal();
        }
        scheduleTask(runner, owner, startTime, defaultPriority);
    }

    /**
     * {@inheritDoc}
     */
    public PeriodicTaskHandle schedulePeriodicTask(Task task, long delay,
                                                   long period) {
        // note the start time
        long startTime = System.currentTimeMillis() + delay;

        if (task == null)
            throw new NullPointerException("Task must not be null");
        if ((delay < 0) || (period < 0))
            throw new IllegalArgumentException("Times must not be null");
        if (isShutdown)
            throw new IllegalStateException("Service is shutdown");

        if (logger.isLoggable(Level.FINEST))
            logger.log(Level.FINEST, "scheduling a periodic task starting " +
                       "at {0}", startTime);

        // persist the task regardless of where it will ultimately run
        Identity owner = transactionProxy.getCurrentOwner();
        TaskRunner runner = getRunner(task, owner, startTime, period);
        String objName = runner.getObjName();

        // check where the owner is active to get the task running
        if (! isMappedLocally(owner)) {
            if (handoffTask(objName, owner))
                return new PeriodicTaskHandleImpl(objName);
            runner.markIgnoreIsLocal();
        }
        PendingTask ptask =
            dataService.getServiceBinding(objName, PendingTask.class);
        dataService.markForUpdate(ptask);
        ptask.setRunningNode(nodeId);

        RecurringTaskHandle handle =
            taskScheduler.scheduleRecurringTask(runner, owner, startTime,
                                                period);
        ctxFactory.joinTransaction().addRecurringTask(objName, handle,
                                                      owner);
        return new PeriodicTaskHandleImpl(objName);
    }

    /**
     * {@inheritDoc}
     */
    public void scheduleNonDurableTask(KernelRunnable task) {
        if (task == null)
            throw new NullPointerException("Task must not be null");
        if (isShutdown)
            throw new IllegalStateException("Service is shutdown");
        if (scheduleNDTaskOp != null)
            scheduleNDTaskOp.report();

        Identity owner = transactionProxy.getCurrentOwner();
        scheduleTask(new NonDurableTask(task, owner), owner,
                     START_NOW, defaultPriority);
    }

    /**
     * {@inheritDoc}
     */
    public void scheduleNonDurableTask(KernelRunnable task, long delay) {
        if (task == null)
            throw new NullPointerException("Task must not be null");
        if (delay < 0)
            throw new IllegalArgumentException("Delay must not be negative");
        if (isShutdown)
            throw new IllegalStateException("Service is shutdown");
        if (scheduleNDTaskDelayedOp != null)
            scheduleNDTaskDelayedOp.report();

        Identity owner = transactionProxy.getCurrentOwner();
        scheduleTask(new NonDurableTask(task, owner), owner,
                     System.currentTimeMillis() + delay, defaultPriority);
    }

    /**
     * {@inheritDoc}
     */
    public void scheduleNonDurableTask(KernelRunnable task,
                                       Priority priority) {
        if (task == null)
            throw new NullPointerException("Task must not be null");
        if (priority == null)
            throw new NullPointerException("Priority must not be null");
        if (isShutdown)
            throw new IllegalStateException("Service is shutdown");
        if (scheduleNDTaskPrioritizedOp != null)
            scheduleNDTaskPrioritizedOp.report();

        Identity owner = transactionProxy.getCurrentOwner();
        scheduleTask(new NonDurableTask(task, owner), owner,
                     START_NOW, priority);
    }

    /**
     * Private helper that creates a {@code KernelRunnable} for the task,
     * also generating a unique name for this task and persisting the
     * associated {@code PendingTask}.
     */
    private TaskRunner getRunner(Task task, Identity identity, long startTime,
                                 long period) {
        logger.log(Level.FINEST, "setting up a pending task");

        // create a new pending task that will be used when the runner runs
        PendingTask ptask =
            new PendingTask(task, startTime, period, identity, dataService);

        // get the name of the new object and bind that into the pending
        // namespace for recovery on startup
        ManagedReference taskRef = dataService.createReference(ptask);
        String objName = DS_PENDING_SPACE + identity.getName() + "." +
            taskRef.getId();
        dataService.setServiceBinding(objName, ptask);

        if (logger.isLoggable(Level.FINEST))
            logger.log(Level.FINEST, "created pending task {0}", objName);

        return new TaskRunner(objName, ptask.getBaseTaskType(), identity);
    }

    /**
     * Private helper that handles scheduling a task by getting a reservation
     * from the scheduler. This is used for both the durable and non-durable
     * tasks, but not for periodic tasks.
     */
    private void scheduleTask(KernelRunnable task, Identity owner,
                              long startTime, Priority priority) {
        if (logger.isLoggable(Level.FINEST))
            logger.log(Level.FINEST, "reserving a task starting " +
                       (startTime == START_NOW ? "now" : "at " + startTime));

        // reserve a space for this task
        try {
            TxnState txnState = ctxFactory.joinTransaction();
            // see if this should be scheduled as a task to run now, or as
            // a task to run after a delay
            if (startTime == START_NOW)
                txnState.addReservation(taskScheduler.
                                        reserveTask(task, owner, priority),
                                        owner);
            else
                txnState.addReservation(taskScheduler.
                                        reserveTask(task, owner, startTime),
                                        owner);
        } catch (TaskRejectedException tre) {
            if (logger.isLoggable(Level.FINE))
                logger.logThrow(Level.FINE, tre,
                                "could not get a reservation");
            throw tre;
        }
    }

    /**
     * Private helper that fetches the task associated with the given name. If
     * this is a non-periodic task, then the task is also removed from the
     * managed map of pending tasks. This method is typically used when a
     * task actually runs. If the Task was managed by the application and
     * has been removed by the application, or another TaskService task has
     * already removed the pending task entry, then this method returns null
     * meaning that there is no task to run.
     */
    PendingTask fetchPendingTask(String objName) {
        PendingTask ptask = null;
        try {
            ptask = dataService.getServiceBinding(objName, PendingTask.class);
        } catch (NameNotBoundException nnbe) {
            // the task was already removed, so check if this is a recurring
            // task, because then we need to cancel it (this may happen if
            // the task was cancelled on a different node than where it is
            // currently running)
            if (recurringMap.containsKey(objName))
                ctxFactory.joinTransaction().
                    cancelRecurringTask(objName, transactionProxy.
                                                 getCurrentOwner());
            else
                ctxFactory.joinTransaction().
                    decrementStatusCount(transactionProxy.getCurrentOwner());
            return null;
        }
        boolean isAvailable = ptask.isTaskAvailable();

        // if it's not periodic, remove both the task and the name binding,
        // checking that this doesn't change the identity's status
        if (! ptask.isPeriodic()) {
            dataService.removeServiceBinding(objName);
            dataService.removeObject(ptask);
            ctxFactory.joinTransaction().
                decrementStatusCount(ptask.getIdentity());
        } else {
            // Make sure that the task is still available, because if it's
            // not, then we need to remove the mapping and cancel the task.
            // Note that this should be a very rare case
            if (! isAvailable)
                cancelPeriodicTask(objName);
        }

        return isAvailable ? ptask : null;
    }

    /**
     * Private helper that cancels a periodic task. This method cancels the
     * underlying recurring task, removes the task and name binding, and
     * notes the cancelled task in the local transaction state.
     */
    private void cancelPeriodicTask(String objName) {
        if (logger.isLoggable(Level.FINEST))
            logger.log(Level.FINEST, "cancelling periodic task {0}", objName);

        TxnState txnState = ctxFactory.joinTransaction();
        PendingTask ptask = null;

        // resolve the task, which checks if the task was already cancelled
        try {
            ptask = dataService.getServiceBinding(objName, PendingTask.class);
        } catch (NameNotBoundException nnbe) {
            throw new ObjectNotFoundException("task was already cancelled");
        }

        // make sure the recurring task gets cancelled on commit
        txnState.cancelRecurringTask(objName, ptask.getIdentity());

        // remove the pending task from the data service
        dataService.removeServiceBinding(objName);
        dataService.removeObject(ptask);
    }

    /**
     * Private helper that notifies the service about a task that failed
     * and is not being re-tried. This happens whenever a task is run by
     * the scheduler, and throws an exception that doesn't request the
     * task be re-tried. In this case, the transaction gets aborted, so
     * the pending task stays in the map. This method is called to start
     * a new task with the sole purpose of creating a new transactional
     * task where the pending task can be removed, if that task is not
     * periodic. Note that this method is not called within an active
     * transaction.
     */
    private void notifyNonRetry(final String objName) {
        if (logger.isLoggable(Level.INFO))
            logger.log(Level.INFO, "trying to remove non-retried task {0}",
                       objName);

        // check if the task is in the recurring map, in which case we don't
        // do anything else, because we don't remove recurring tasks except
        // when they're cancelled...note that this may yield a false negative,
        // because in another transaction the task may have been cancelled and
        // therefore already removed from this map, but this is an extremely
        // rare case, and at worst it simply causes a task to be scheduled
        // that will have no effect once run (because fetchPendingTask will
        // look at the pending task data, see that it's recurring, and
        // leave it in the map)
        if (recurringMap.containsKey(objName))
            return;
        
        TransactionRunner transactionRunner =
            new TransactionRunner(new NonRetryCleanupRunnable(objName));
        try {
            taskScheduler.scheduleTask(transactionRunner,
                                       transactionProxy.getCurrentOwner());
        } catch (TaskRejectedException tre) {
            // Note that this should (essentially) never happen, but if it
            // does then the pending task will always remain, and this node
            // will never consider this identity as inactive
            if (logger.isLoggable(Level.WARNING))
                logger.logThrow(Level.WARNING, tre, "could not schedule " +
                                "task to remove non-retried task {0}: " +
                                "giving up", objName);
            throw tre;
        }
    }

    /**
     * Private helper runnable that cleans up after a non-retried task. See
     * block comment above in notifyNonRetry for more detail.
     */
    private class NonRetryCleanupRunnable implements KernelRunnable {
        private final String objName;
        NonRetryCleanupRunnable(String objName) {
            this.objName = objName;
        }
        /** {@inheritDoc} */
        public String getBaseTaskType() {
            return NonRetryCleanupRunnable.class.getName();
        }
        /** {@inheritDoc} */
        public void run() throws Exception {
            if (isShutdown) {
                if (logger.isLoggable(Level.WARNING))
                    logger.log(Level.WARNING, "Service is shutdown, so a " +
                               "non-retried task {0} will not be removed",
                               objName);
                throw new IllegalStateException("Service is shutdown");
            }
            fetchPendingTask(objName);
        }
    }

    /**
     * Private class that is used to track state associated with a single
     * transaction and handle commit and abort operations.
     */
    private class TxnState extends TransactionContext {
        private HashSet<TaskReservation> reservationSet = null;
        private HashMap<String,RecurringDetail> addedRecurringMap = null;
        private HashSet<String> cancelledRecurringSet = null;
        private HashMap<Identity,Integer> statusMap =
            new HashMap<Identity,Integer>();
        /** Creates context tied to the given transaction. */
        TxnState(Transaction txn) {
            super(txn);
        }
        /** {@inheritDoc} */
        public void commit() {
            // cancel the cancelled periodic tasks...
            if (cancelledRecurringSet != null) {
                for (String objName : cancelledRecurringSet) {
                    RecurringDetail detail = recurringMap.remove(objName);
                    if (detail != null) {
                        detail.handle.cancel();
                        removeHandleForIdentity(detail.handle, detail.identity);
                        decrementStatusCount(detail.identity);
                    }
                }
            }
            // ...and hand-off any pending status votes
            for (Entry<Identity,Integer> entry : statusMap.entrySet()) {
                int countChange = entry.getValue();
                if (countChange != 0)
                    submitStatusChange(entry.getKey(), countChange);
            }
            // with the status counts updated, use the reservations...
            if (reservationSet != null)
                for (TaskReservation reservation : reservationSet)
                    reservation.use();
            // ... and start the periodic tasks
            if (addedRecurringMap != null) {
                for (Entry<String,RecurringDetail> entry :
                         addedRecurringMap.entrySet()) {
                    RecurringDetail detail = entry.getValue();
                    recurringMap.put(entry.getKey(), detail);
                    addHandleForIdentity(detail.handle, detail.identity);
                    detail.handle.start();
                }
            }
        }
        /** {@inheritDoc} */
        public void abort(boolean retryable) {
            // cancel all the reservations for tasks and recurring tasks that
            // were made during the transaction
            if (reservationSet != null)
                for (TaskReservation reservation : reservationSet)
                    reservation.cancel();
            if (addedRecurringMap != null)
                for (RecurringDetail detail : addedRecurringMap.values())
                    detail.handle.cancel();
        }
        /** Adds a reservation to use at commit-time. */
        void addReservation(TaskReservation reservation, Identity identity) {
            if (reservationSet == null)
                reservationSet = new HashSet<TaskReservation>();
            reservationSet.add(reservation);
            incrementStatusCount(identity);
        }
        /** Adds a handle to start at commit-time. */
        void addRecurringTask(String name, RecurringTaskHandle handle,
                              Identity identity) {
            if (addedRecurringMap == null)
                addedRecurringMap = new HashMap<String,RecurringDetail>();
            addedRecurringMap.put(name, new RecurringDetail(handle, identity));
            incrementStatusCount(identity);
        }
        /**
         * Tries to cancel the associated recurring task, recognizing whether
         * the task was scheduled within this transaction or previously.
         */
        void cancelRecurringTask(String name, Identity identity) {
            RecurringDetail detail = null;
            if ((addedRecurringMap == null) ||
                ((detail = addedRecurringMap.remove(name)) == null)) {
                // the task wasn't created in this transaction, so make
                // sure that it gets cancelled at commit
                if (cancelledRecurringSet == null)
                    cancelledRecurringSet = new HashSet<String>();
                cancelledRecurringSet.add(name);
            } else {
                // the task was created in this transaction, so we just have
                // to make sure that it doesn't start
                detail.handle.cancel();
                decrementStatusCount(identity);
            }
        }
        /** Notes that a task has been added for the given identity. */
        void incrementStatusCount(Identity identity) {
            if (statusMap.containsKey(identity))
                statusMap.put(identity, statusMap.get(identity) + 1);
            else
                statusMap.put(identity, 1);
        }
        /** Notes that a task has been removed for the given identity. */
        void decrementStatusCount(Identity identity) {
            if (statusMap.containsKey(identity))
                statusMap.put(identity, statusMap.get(identity) - 1);
            else
                statusMap.put(identity, -1);
        }
    }

    /** Private implementation of {@code TransactionContextFactory}. */
    private class TransactionContextFactoryImpl
        extends TransactionContextFactory<TxnState>
    {
        /** Creates an instance with the given proxy. */
        TransactionContextFactoryImpl(TransactionProxy proxy) {
            super(proxy);
        }
        /** {@inheritDoc} */
        protected TxnState createContext(Transaction txn) {
            return new TxnState(txn);
        }
    }

    /**
     * Private implementation of {@code KernelRunnable} that is used to
     * run the {@code Task}s scheduled by the application.
     */
    private class TaskRunner implements KernelRunnable {
        private final String objName;
        private final String objTaskType;
        private final Identity taskIdentity;
        private boolean doLocalCheck = true;
        TaskRunner(String objName, String objTaskType, Identity taskIdentity) {
            this.objName = objName;
            this.objTaskType = objTaskType;
            this.taskIdentity = taskIdentity;
        }
        String getObjName() {
            return objName;
        }
        /**
         * This method is used in the case where the associated identity is
         * not mapped to the local node, but no assignment exists yet. In
         * these cases, the task is just run locally, so no check should be
         * done to see if the identity is local.
         */
        void markIgnoreIsLocal() {
            doLocalCheck = false;
        }
        /** {@inheritDoc} */
        public String getBaseTaskType() {
            return objTaskType;
        }
        /** {@inheritDoc} */
        public void run() throws Exception {
            if (isShutdown)
                return;

            // check that the task's identity is still active on this node,
            // and if not then return, cancelling the task if it's recurring
            if ((doLocalCheck) && (! isMappedLocally(taskIdentity))) {
                RecurringDetail detail = recurringMap.remove(objName);
                if (detail != null) {
                    detail.handle.cancel();
                    removeHandleForIdentity(detail.handle, detail.identity);
                }
                submitStatusChange(taskIdentity, -1);
                return;
            }

            try {
                // run the task in a transactional context
                (new TransactionRunner(new KernelRunnable() {
                        public String getBaseTaskType() {
                            return objTaskType;
                        }
                        public void run() throws Exception {
                            // fetch the task, making sure that it's available
                            PendingTask ptask = fetchPendingTask(objName);
                            if (ptask == null) {
                                logger.log(Level.FINER, "tried to run a task "
                                           + "that was removed previously " +
                                           "from the data service; giving up");
                                return;
                            }
                            // check that the task isn't perdiodic and now
                            // running on another node
                            if (ptask.isPeriodic()) {
                                long node = ptask.getRunningNode();
                                if (node != nodeId) {
                                    // someone else picked up this task, so
                                    // cancel it locally and we're done
                                    ctxFactory.joinTransaction().
                                        cancelRecurringTask(objName,
                                                            taskIdentity);
                                    return;
                                }
                            }
                            if (logger.isLoggable(Level.FINEST))
                                logger.log(Level.FINEST, "running task {0} " +
                                           "scheduled to run at {1}",
                                           objName, ptask.getStartTime());
                            // run the task
                            ptask.run();
                        }
                    })).run();
            } catch (Exception e) {
                // catch exceptions just before they go back to the scheduler
                // to see if the task will be re-tried...if not, then we need
                // notify the service
                if ((! (e instanceof ExceptionRetryStatus)) ||
                    (! ((ExceptionRetryStatus)e).shouldRetry()))
                    notifyNonRetry(objName);
                throw e;
            }
        }
    }

    /**
     * Private wrapper class for all non-durable tasks. This simply makes
     * sure that when a non-durable task runs, the status count for the
     * associated identity is decremented.
     */
    private class NonDurableTask implements KernelRunnable {
        private final KernelRunnable runnable;
        private final Identity identity;
        NonDurableTask(KernelRunnable runnable, Identity identity) {
            this.runnable = runnable;
            this.identity = identity;
        }
        public String getBaseTaskType() {
            return runnable.getBaseTaskType();
        }
        public void run() throws Exception {
            if (isShutdown)
                return;
            try {
                runnable.run();
            } catch (Throwable t) {
                // only if the task isn't going to be retried, submit the
                // status change now
                if ((! (t instanceof ExceptionRetryStatus)) ||
                    (! ((ExceptionRetryStatus)t).shouldRetry()))
                    submitStatusChange(identity, -1);

                if (t instanceof Error)
                    throw (Error)t;
                else
                    throw (Exception)t;
            }
            submitStatusChange(identity, -1);
        }
    }

    /**
     * Private helper that restarts all of the tasks associated with the
     * given identity. This must be called within a transaction.
     */
    private void restartTasks(String identityName) {
        // start iterating from the root of the pending task namespace
        String prefix = DS_PENDING_SPACE + identityName + ".";
        String objName = dataService.nextServiceBoundName(prefix);
        int taskCount = 0;

        // loop through all bound names for the given identity, starting
        // each pending task in a separate transaction
        while ((objName != null) && (objName.startsWith(prefix))) {
            scheduleNonDurableTask(new TransactionRunner(
                    new TaskRestartRunner(objName)));
            objName = dataService.nextServiceBoundName(objName);
            taskCount++;
        }

        if (logger.isLoggable(Level.CONFIG))
            logger.log(Level.CONFIG, "re-scheduled {0} tasks for identity {1}",
                       taskCount, identityName);
    }

    /**
     * Private helper that restarts a single named task. This must be called
     * within a transaction.
     */
    private void restartTask(String objName) {
        PendingTask ptask = null;
        try {
            ptask = dataService.getServiceBinding(objName, PendingTask.class);
        } catch (NameNotBoundException nnbe) {
            // this happens when a task is scheduled for an identity that
            // hasn't yet been mapped or is in the process of being mapped,
            // so we can just return, since the task has already been run
            return;
        }

        // check that the task is supposed to run here, or if not, that
        // we were able to hand it off
        Identity identity = ptask.getIdentity();
        if (! isMappedLocally(identity)) {
            // if we handed off the task, we're done
            if (handoffTask(objName, identity))
                return;
        }

        TaskRunner runner = new TaskRunner(objName, ptask.getBaseTaskType(),
                                           identity);
        runner.markIgnoreIsLocal();

        if (ptask.getPeriod() == PERIOD_NONE) {
            // this is a non-periodic task
            scheduleTask(runner, identity, ptask.getStartTime(), 
                         defaultPriority);
        } else {
            // this is a periodic task...there is a rare but possible
            // scenario where a periodic task starts for an un-mapped
            // identity, and then the identity gets mapped to this node,
            // which would cause two copies of the task to start, so
            // the recurringMap is checked to make sure it doesn't already
            // contain the task being restarted
            if (recurringMap.containsKey(objName))
                return;

            // get the times associated with this task, and if the start
            // time has already passed, figure out the next period
            // interval from now to use as the new start time
            long start = ptask.getStartTime();
            long now = System.currentTimeMillis();
            long period = ptask.getPeriod();
            if (start < now)
                start += (((int)((now - start) / period)) + 1) * period;

            // mark the task as running on this node so that it doesn't
            // also run somewhere else
            dataService.markForUpdate(ptask);
            ptask.setRunningNode(nodeId);

            RecurringTaskHandle handle =
                taskScheduler.scheduleRecurringTask(runner, identity, start,
                                                    period);
            ctxFactory.joinTransaction().
                addRecurringTask(objName, handle, identity);
        }
    }

    /** A private runnable used to re-start a single task; this must be run
        within a transaction. */
    private class TaskRestartRunner implements KernelRunnable {
        private final String objName;
        TaskRestartRunner(String objName) {
            this.objName = objName;
        }
        public String getBaseTaskType() {
            return getClass().getName();
        }
        public void run() throws Exception {
            restartTask(objName);
        }
    }

    /** A private extension of HashSet to provide type info. */
    private static class StringHashSet extends ScalableHashSet<String> {
        private static final long serialVersionUID = 1;
    }

    /** A private class to track details of recurring tasks. */
    private static class RecurringDetail {
        final RecurringTaskHandle handle;
        final Identity identity;
        RecurringDetail(RecurringTaskHandle handle, Identity identity) {
            this.handle = handle;
            this.identity = identity;
        }
    }

    /** Private helper to add a recurring handle to the set for an identity. */
    private void addHandleForIdentity(RecurringTaskHandle handle,
                                      Identity identity) {
        synchronized (identityRecurringMap) {
            Set<RecurringTaskHandle> set = identityRecurringMap.get(identity);
            if (set == null) {
                set = new HashSet<RecurringTaskHandle>();
                identityRecurringMap.put(identity, set);
            }
            set.add(handle);
        }
    }

    /**
     * Private helper to remove a recurring handle from the set for an
     * identity.
     */
    private void removeHandleForIdentity(RecurringTaskHandle handle,
                                         Identity identity) {
        synchronized (identityRecurringMap) {
            Set<RecurringTaskHandle> set = identityRecurringMap.get(identity);
            if (set != null)
                set.remove(handle);
            if (set.isEmpty())
                identityRecurringMap.remove(identity);
        }
    }

    /** Private helper that cancels all recurring tasks for an identity. */
    private void cancelHandlesForIdentity(Identity identity) {
        synchronized (identityRecurringMap) {
            Set<RecurringTaskHandle> set =
                identityRecurringMap.remove(identity);
            if (set != null) {
                for (RecurringTaskHandle handle : set)
                    handle.cancel();
            }
        }
    }

    /**
     * Private helper method that hands-off a durable task from the current
     * node to a new node. The task needs to have already been persisted
     * as a {@code PendingTask} under the given name binding. This method
     * must be called in the context of a valid transaction. If this method
     * returns {@code false} then the task was not handed-off, and so it
     * must be run on the local node.
     * NOTE: we may want to revisit this final assumption, perhaps delaying
     * such tasks, or coming up with some other policy
     */
    private boolean handoffTask(String objName, Identity identity) {
        Node handoffNode = null;
        try {
            handoffNode = nodeMappingService.getNode(identity);
        } catch (UnknownIdentityException uie) {
            // this should be a rare case, but in the event that there isn't
            // a mapping available, there's really nothing to be done except
            // just run the task locally, and in a separate thread try to get
            // the assignment taken care of
            if (logger.isLoggable(Level.INFO))
                logger.logThrow(Level.INFO, uie, "No mapping exists for " +
                                "identity {0} so task {1} will run locally",
                                identity.getName(), objName);
            assignNode(identity);
            return false;
        }

        // since the call to get an assigned node can actually return a
        // failed node, check for this case first
        if (! handoffNode.isAlive()) {
            // since the mapped node is down, run the task locally
            if (logger.isLoggable(Level.INFO))
                logger.log(Level.INFO, "Mapping for identity {0} was to " +
                           "node {1} which has failed so task {2} will " +
                           "run locally", identity.getName(),
                           handoffNode.getId(), objName);
            return false;
        }

        long newNodeId = handoffNode.getId();
        if (newNodeId == nodeId) {
            // a timing issue caused us to try handing-off to ourselves, so
            // just return from here
            return false;
        }

        String handoffName = DS_HANDOFF_SPACE + String.valueOf(newNodeId);
        if (logger.isLoggable(Level.FINER))
            logger.log(Level.FINER, "Handing-off task {0} to node {1}",
                       objName, newNodeId);
        try {
            StringHashSet set =
                dataService.getServiceBinding(handoffName, StringHashSet.class);
            set.add(objName);
        } catch (NameNotBoundException nnbe) {
            // this will only happen in the unlikely event that the identity
            // has been assigned to a node that is still coming up and hasn't
            // bound its hand-off set yet, in which case the new node will
            // run this task when it learns about the identity mapping
        }

        return true;
    }

    /** Private helper that kicks off a thread to do node assignment. */
    private void assignNode(final Identity identity) {
        (new Thread(new Runnable() {
                public void run() {
                    nodeMappingService.assignNode(TaskServiceImpl.class,
                                                  identity);
                }
            })).start();
    }

    /**
     * Private runnable that periodically checks to see if any tasks have
     * been handed-off from another node.
     */
    private class HandoffRunner implements KernelRunnable {
        /** {@inheritDoc} */
        public String getBaseTaskType() {
            return HandoffRunner.class.getName();
        }
        /** {@inheritDoc} */
        public void run() throws Exception {
            StringHashSet set =
                dataService.getServiceBinding(localHandoffSpace,
                                              StringHashSet.class);
            if (! set.isEmpty()) {
                Iterator<String> it = set.iterator();
                while (it.hasNext()) {
                    scheduleNonDurableTask(new TransactionRunner(
                            new TaskRestartRunner(it.next())));
                    it.remove();
                }
            }
        }
    }

    /**
     * Private helper that checks whether the given {@code Identity} is
     * currently thought to be mapped to the local node. This does not need
     * to be called from within a transaction.
     */
    private boolean isMappedLocally(Identity identity) {
        synchronized (mappedIdentitySet) {
            return mappedIdentitySet.contains(identity);
        }
    }

    /**
     * Private helper that checks if the given {@code Identity} is running
     * any tasks on the local node. This does not need to be called from
     * within a transaction.
     */
    private boolean isActiveLocally(Identity identity) {
        synchronized (activeIdentityMap) {
            return activeIdentityMap.containsKey(identity);
        }
    }

    /**
     * Private helper that accepts status change votes. This method does
     * not access any transactional context or other services, but may
     * be called in a transaction. Note that a count of 0 is ignored.
     */
    private void submitStatusChange(Identity identity, int change) {
        // a change of zero means that nothing would change
        if (change == 0)
            return;

        // apply the count change, and see if this changes the status
        synchronized (activeIdentityMap) {
            boolean active;
            if (activeIdentityMap.containsKey(identity)) {
                // there is currently a count, so we'll need to see what
                // affect the change has
                int current = activeIdentityMap.get(identity) + change;
                assert current >= 0 : "task count went negative for " +
                    "identity: " + identity.getName();
                if (current == 0) {
                    activeIdentityMap.remove(identity);
                    active = false;
                } else {
                    activeIdentityMap.put(identity, current);
                    return;
                }
            } else {
                // unless the count is negative, we're going active
                assert change >= 0 : "task count went negative for identity: " +
                    identity.getName();
                activeIdentityMap.put(identity, change);
                active = true;
            }

            // if we got here then there is a change in the status, as noted
            // by the "active" boolean flag
            TimerTask task = statusTaskMap.remove(identity);
            if (task != null) {
                // if there was a timer task pending, then we've just negated
                // it with the new status, so cancel the task
                task.cancel();
            } else {
                // there was no pending task, so set one up
                task = new StatusChangeTask(identity, active);
                statusTaskMap.put(identity, task);
                statusUpdateTimer.schedule(task, voteDelay);
            }
        }
    }

    /** Private TimerTask implementation for delaying status votes. */
    private class StatusChangeTask extends TimerTask {
        private final Identity identity;
        private final boolean active;
        StatusChangeTask(Identity identity, boolean active) {
            this.identity = identity;
            this.active = active;
        }
        public void run() {
            // remove this handle from the pending map, and if this is
            // successful, then no one has tried to cancel this task, so
            // finish running
            if (statusTaskMap.remove(identity) != null) {
                try {
                    nodeMappingService.setStatus(TaskServiceImpl.class,
                                                 identity, active);
                } catch (UnknownIdentityException uie) {
                    if (active)
                        nodeMappingService.assignNode(TaskServiceImpl.class,
                                                      identity);
                }
            }
        }
    }

    /** {@inheritDoc} */
    public void mappingAdded(Identity id, Node oldNode) {
        if (isShutdown)
            return;

        // keep track of the new identity, returning if the identity was
        // already mapped to this node
        synchronized (mappedIdentitySet) {
            if (! mappedIdentitySet.add(id))
                return;
        }

        // start-up the pending tasks for this identity
        final String identityName = id.getName();
        try {
            taskScheduler.
                runTransactionalTask(new KernelRunnable() {
                        public String getBaseTaskType() {
                            return NAME + ".TaskRestartRunner";
                        }
                        public void run() throws Exception {
                            restartTasks(identityName);
                        }
                    }, appOwner);
        } catch (Exception e) {
            // this should only happen if the restart task fails, which
            // would indicate some kind of corrupted state
            throw new AssertionError("Failed to restart tasks for " +
                                     id.getName() + ": " + e.getMessage());
        }
    }

    /** {@inheritDoc} */
    public void mappingRemoved(Identity id, Node newNode) {
        if (isShutdown)
            return;

        // if the newNode is null, this means that the identity has been
        // removed entirely, so if there are still local tasks, keep
        // running them and push-back on the mapping service
        if ((newNode == null) && (isActiveLocally(id))) {
            nodeMappingService.assignNode(TaskServiceImpl.class, id);
            return;
        }
        
        // note that the identity is no longer on this node
        synchronized (mappedIdentitySet) {
            mappedIdentitySet.remove(id);
        }
        // cancel all of the identity's recurring tasks
        cancelHandlesForIdentity(id);
        
        // immediately vote that the identity is active iif there are
        // still tasks running locally
        if (isActiveLocally(id)) {
            try {
                nodeMappingService.setStatus(TaskServiceImpl.class, id, true);
            } catch (UnknownIdentityException uie) {
                nodeMappingService.assignNode(TaskServiceImpl.class, id);
            }
        }
    }

    /**
     * Private implementation of {@code PeriodicTaskHandle} that is
     * provided to application developers so they can cancel their tasks
     * in the future. This class uses the internally assigned name to
     * reference the task in the future, and uses the thread local service
     * reference to find its service.
     */
    private static class PeriodicTaskHandleImpl
        implements PeriodicTaskHandle, Serializable
    {
        private static final long serialVersionUID = 1;
        private final String objName;
        PeriodicTaskHandleImpl(String objName) {
            this.objName = objName;
        }
        /** {@inheritDoc} */
        public void cancel() {
            TaskServiceImpl service = TaskServiceImpl.transactionProxy.
                getService(TaskServiceImpl.class);
            if (service.isShutdown)
                throw new IllegalStateException("Service is shutdown");
            service.cancelPeriodicTask(objName);
        }
    }

}
