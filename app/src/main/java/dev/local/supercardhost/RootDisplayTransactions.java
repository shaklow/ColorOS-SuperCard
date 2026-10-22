package dev.local.supercardhost;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.Surface;

/** Synchronous v1 IPC. Each operation retains the endpoint and nonce it started with. */
final class RootDisplayTransactions {
    static final class DisplayToken {
        final int id;
        final IBinder callback;
        DisplayToken(int id, IBinder callback) { this.id = id; this.callback = callback; }
    }

    private RootDisplayTransactions() { }

    static void hello(IBinder endpoint, String nonce, IBinder lifetime) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            header(data, nonce); data.writeStrongBinder(lifetime);
            transact(endpoint, RootDisplayProtocol.HELLO, data, reply);
        } finally { data.recycle(); reply.recycle(); }
    }

    static DisplayToken create(RootDisplayConnection.Session session, String name,
            int width, int height, int density, Surface surface, int flags) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            header(data, session.nonce);
            data.writeString(name); data.writeInt(width); data.writeInt(height); data.writeInt(density);
            data.writeTypedObject(surface, 0); data.writeInt(flags);
            transact(session.endpoint, RootDisplayProtocol.CREATE, data, reply);
            return new DisplayToken(reply.readInt(), reply.readStrongBinder());
        } finally { data.recycle(); reply.recycle(); }
    }

    static void release(RootDisplayConnection.Session session, int id) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            header(data, session.nonce); data.writeInt(id);
            transact(session.endpoint, RootDisplayProtocol.RELEASE, data, reply);
        } finally { data.recycle(); reply.recycle(); }
    }

    private static void header(Parcel data, String nonce) {
        data.writeInterfaceToken(RootDisplayProtocol.DESCRIPTOR); data.writeString(nonce);
    }

    private static void transact(IBinder endpoint, int code, Parcel data, Parcel reply) throws RemoteException {
        if (!endpoint.transact(code, data, reply, 0))
            throw new IllegalStateException("Unsupported root display transaction " + code);
        reply.readException();
    }
}
