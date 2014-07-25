package tor;

import tor.util.ByteFifo;

import java.io.IOException;
import java.util.Arrays;

public class TorStream {

	int streamId;
	TorCircuit circ;

    enum STATES { CONNECTING, READY, DESTROYED };
    STATES state = STATES.CONNECTING;

	ByteFifo recv = new ByteFifo(4096);
	TorStreamListener listener;

    int recvWindow = 500;
    final static int recvWindowIncrement = 50;
	
	public TorStream(int streamId, TorCircuit circ, TorStreamListener list) {
		this.streamId = streamId;
		this.circ = circ;
		listener = list;
	}

    public void setState(STATES newState) {
        synchronized (this) {
            state = newState;
            this.notifyAll();
        }
    }

    public void waitForState(STATES desired) {
        while(true) {
            synchronized (this) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            if(state.equals(desired))
                return;
        }
    }
	/**
	 * Get received data from this stream (e.g. data received from remote end)
	 * 
	 * @param bytes How many bytes? -1 for max.
	 * 
	 * @return Received bytes
	 */
	public byte[] recv(int bytes, boolean block) throws IOException {
        if(recv.isEmpty() && state == STATES.DESTROYED)
            throw new IOException("stream destroyed");
        if (block) {
            synchronized (this) {
                while (recv.isEmpty()) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
		return recv.get(bytes);
	}

	
	/**
	 * Send bytes down this stream
	 * 
	 * @param b Bytes to send
	 */
	public void send(byte b[]) throws IOException {
        if(state == STATES.DESTROYED)
            throw new IOException("stream destroyed");

        final int maxDataLen = 509-1-2-2-4-2;
        for(int i=0; i<Math.ceil(b.length/(double)maxDataLen)*maxDataLen; i+=maxDataLen) {
            byte data[] = Arrays.copyOfRange(b, i, Math.min(b.length, i + maxDataLen));
            circ.send(data, TorCircuit.RELAY_DATA, false, (short) streamId);
        }
	}

    public void destroy() throws IOException {
        if(state == STATES.DESTROYED)
            return; // don't redo!
        setState(STATES.DESTROYED);
        circ.send(new byte[] {6}, TorCircuit.RELAY_END, false, (short)streamId);
        circ.streams.remove(new Integer(streamId));
    }
	
	/**
	 * Internal function. Used to add received bytes to object.
	 * 
	 * @param b Bytes
	 */
	protected void _putRecved(byte b[]) {
        recvWindow--;
        if(recvWindow < 450) {
            try {
                //System.out.println("sent SENDME (STREAM) "+recvWindow);
                circ.send(null, TorCircuit.RELAY_SENDME, false, (short)streamId);
            } catch (IOException e) {
                e.printStackTrace();
            }
            recvWindow += recvWindowIncrement;
        }

		recv.put(b);
		if(listener != null)
			listener.dataArrived(this);
        synchronized (this) {
            this.notifyAll();
        }
	}
	
	public void notifyDisconnect() {
        setState(STATES.DESTROYED);
		if(listener != null)
			listener.disconnected(this);
	}
	
	public void notifyConnect() {
        setState(STATES.READY);
		if(listener != null)
			listener.connected(this);
	}
	
	public interface TorStreamListener {
		public void dataArrived(TorStream s);
		public void connected(TorStream s);
		public void disconnected(TorStream s);
		public void failure(TorStream s);
	}
}
