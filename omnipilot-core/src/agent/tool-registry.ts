import { Tool } from '../llm/models.js';

export class ToolRegistry {
  public static getAgentTools(): Tool[] {
    return [
      {
        type: 'function',
        function: {
          name: 'read_file',
          description: 'Reads the full UTF-8 text contents of a specified file from the workspace.',
          parameters: {
            type: 'object',
            properties: {
              filePath: {
                type: 'string',
                description: 'The relative or absolute file path to read.'
              }
            },
            required: ['filePath']
          }
        }
      },
      {
        type: 'function',
        function: {
          name: 'write_file',
          description: 'Creates a new file or overwrites an existing file with new content.',
          parameters: {
            type: 'object',
            properties: {
              filePath: {
                type: 'string',
                description: 'The file path to write to.'
              },
              content: {
                type: 'string',
                description: 'The text content to write into the file.'
              }
            },
            required: ['filePath', 'content']
          }
        }
      },
      {
        type: 'function',
        function: {
          name: 'run_command',
          description: 'Executes a command line instruction in the IDE terminal.',
          parameters: {
            type: 'object',
            properties: {
              command: {
                type: 'string',
                description: 'The shell command to execute.'
              }
            },
            required: ['command']
          }
        }
      }
    ];
  }
}
