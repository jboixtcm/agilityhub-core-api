// INC-64 local rehearsal only (bin/deploy-smoke): the fictional club's back-office host becomes a VERIFIED clubs-admin domain
// while Caddy runs. The local stack has no DNS egress, so this write stands for the console's verification commit.
const database = new Mongo(process.env.LOCAL_MONGODB_URI).getDB(process.env.MONGODB_DATABASE);
const host = 'approved-admin.localhost';
const result = database.clubs.updateOne({slug: 'canic', 'domains.host': {$ne: host}},
    {$push: {domains: {host: host, app: 'clubs-admin', status: 'VERIFIED', verifiedAt: new Date(), primary: false}}});
if (result.modifiedCount !== 1) throw new Error('Expected to add the fictional back-office domain once');
print('Local fixture back-office domain verified');
