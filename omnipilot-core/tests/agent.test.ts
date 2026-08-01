import { ToolRegistry } from '../src/agent/tool-registry.js';

describe('ToolRegistry Unit Tests', () => {
  test('should return correct tool definitions', () => {
    const tools = ToolRegistry.getAgentTools();
    expect(tools).toHaveLength(3);
    const names = tools.map(t => t.function.name);
    expect(names).toContain('read_file');
    expect(names).toContain('write_file');
    expect(names).toContain('run_command');
  });
});
