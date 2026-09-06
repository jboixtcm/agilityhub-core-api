// Only initialize an empty replica set; preserve the configuration on restarts.
try {
    rs.status();
} catch (error) {
    if (error.code !== 94) {
        throw error;
    }
    rs.initiate({
        _id: process.env.MONGODB_REPLICA_SET,
        members: [{_id: 0, host: "mongo:27017"}]
    });
}

// The API may start only after the node can accept transactional writes.
if (!db.hello().isWritablePrimary) {
    quit(1);
}
