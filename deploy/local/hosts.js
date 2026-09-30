// Fictional local rehearsal only: the release must use verified public domains.
const database = new Mongo(process.env.LOCAL_MONGODB_URI).getDB(process.env.MONGODB_DATABASE);
const club = database.clubs.findOne({slug: 'canic'});
if (!club || !Array.isArray(club.domains)) throw new Error('Expected seeded demo domains');
const domains = club.domains.map(domain => ({...domain,
    host: domain.host === 'app.example.test' ? 'clubs.localhost'
        : domain.host === 'admin.example.test' ? 'clubsadmin.localhost' : domain.host}));
database.clubs.updateOne({_id: club._id}, {$set: {domains, status: 'ACTIVE'}});
print('Local fixture domains mapped and fictional club activated');
