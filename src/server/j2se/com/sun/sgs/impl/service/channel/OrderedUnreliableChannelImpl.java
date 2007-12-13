/*
 * Copyright 2007 Sun Microsystems, Inc.
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

package com.sun.sgs.impl.service.channel;

import com.sun.sgs.app.ClientSession;
import com.sun.sgs.app.Delivery;
import com.sun.sgs.impl.sharedutil.HexDumper;
import java.util.Set;
import java.util.logging.Level;

class OrderedUnreliableChannelImpl extends ChannelImpl {
    
    /** The serialVersionUID for this class. */
    private final static long serialVersionUID = 1L;

    OrderedUnreliableChannelImpl(Delivery delivery) {
	super(delivery);
    }
    
    protected void sendToAllMembers(final byte[] channelMessage) {
	if (logger.isLoggable(Level.FINEST)) {
	    logger.log(
		Level.FINEST, "sendToAllMembers channel:{0}, message:{1}",
		this, HexDumper.format(channelMessage));
	}
    }
}
