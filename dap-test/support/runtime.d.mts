export function createRuntimeDirectory(): Promise<string>;
export function ensureTestRepository(repoPath: string): Promise<void>;
export function cleanupRuntimeDirectory(root: string, keep?: boolean): Promise<boolean>;
