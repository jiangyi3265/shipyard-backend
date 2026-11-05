// Integration scripts are restricted to the isolated localhost test service.
// Credentials are supplied by the caller and never committed or printed.
const password=process.env.SHIPYARD_TEST_PASSWORD;
if(!password) throw new Error('Set SHIPYARD_TEST_PASSWORD to the password provisioned in the isolated test database.');
export const credentials={password,accounts:['admin','sy_leader','sy_manager','sy_owner','sy_accountant','sy_safety']};
