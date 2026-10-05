// Bootstrap only on a new volume. Environment changes never silently rotate users.
const admin = db.getSiblingDB('admin');
admin.auth(process.env.MONGO_INITDB_ROOT_USERNAME, process.env.MONGO_INITDB_ROOT_PASSWORD);
try {
    rs.status();
} catch (error) {
    if (error.code !== 94) throw error;
    rs.initiate({_id: 'rs0', members: [{_id: 0, host: 'mongo:27017'}]});
}
if (!db.hello().isWritablePrimary) quit(1);
if (!admin.getUser(process.env.MONGODB_USERNAME)) {
    admin.createUser({user: process.env.MONGODB_USERNAME, pwd: process.env.MONGODB_PASSWORD,
        roles: [{role: 'readWrite', db: process.env.MONGODB_DATABASE}]});
}
if (!admin.getUser(process.env.MONGO_BACKUP_USERNAME)) {
    admin.createUser({user: process.env.MONGO_BACKUP_USERNAME, pwd: process.env.MONGO_BACKUP_PASSWORD,
        roles: [{role: 'backup', db: 'admin'}]});
}
// Test both accounts too: stale environment credentials must make startup fail.
const app = new Mongo('mongodb://127.0.0.1:27017/?directConnection=true').getDB('admin');
app.auth(process.env.MONGODB_USERNAME, process.env.MONGODB_PASSWORD);
const backup = new Mongo('mongodb://127.0.0.1:27017/?directConnection=true').getDB('admin');
backup.auth(process.env.MONGO_BACKUP_USERNAME, process.env.MONGO_BACKUP_PASSWORD);
