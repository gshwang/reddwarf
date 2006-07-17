
/*
 * Service.java
 *
 * Created by: seth proctor (sp76946)
 * Created on: Thu Jul  6, 2006	 4:58:28 PM
 * Desc: 
 *
 */

package com.sun.sgs.service;


/**
 * This is the core interface used for all services that participate in
 * transactions. Most implementations of <code>Service</code> will
 * actually implement specific sub-interfaces like <code>DataService</code>.
 *
 * @since 1.0
 * @author James Megquier
 * @author Seth Proctor
 */
public interface Service
{

    /**
     * Returns the identifier used to identify this service.
     *
     * @return the service's identifier
     */
    public String getIdentifier();

    /**
     * Tells the <code>Service</code> to prepare for commiting its
     * state assciated with the given transaction.
     *
     * @param txn the <code>Transaction</code> state
     *
     * FIXME: what does this throw?
     */
    public void prepare(Transaction txn) throws Exception;

    /**
     * Tells the <code>Service</code> to commit its state associated
     * with the previously prepared transaction.
     *
     * @param txn the <code>Transaction</code> state
     *
     * FIXME: what does this throw?
     */
    public void commit(Transaction txn) throws Exception;

    /**
     * Tells the <code>Service</code> to both prepare and commit its
     * state associated with the given transaction. This is provided as
     * an optimization for cases where the sysem knows that a given
     * transaction cannot fail, or can be partially backed out.
     *
     * @param txn the <code>Transaction</code> state
     *
     * FIXME: what does this throw?
     */
    public void prepareAndCommit(Transaction txn) throws Exception;

    /**
     * Tells the <code>Service</code> to abort its involvement with
     * the given transaction.
     *
     * @param txn the <code>Transaction</code> state
     */
    public void abort(Transaction txn);

}
