import { inputValue } from './dom';

describe('inputValue', () => {
  function eventFrom(element: HTMLElement): Event {
    const event = new Event('input');
    Object.defineProperty(event, 'target', { value: element });
    return event;
  }

  it('reads an input value', () => {
    const input = document.createElement('input');
    input.value = 'Platform';
    expect(inputValue(eventFrom(input))).toBe('Platform');
  });

  it('reads a textarea value', () => {
    const textarea = document.createElement('textarea');
    textarea.value = 'A description';
    expect(inputValue(eventFrom(textarea))).toBe('A description');
  });

  it('reads a select value', () => {
    const select = document.createElement('select');
    const option = document.createElement('option');
    option.value = '10';
    select.append(option);
    select.value = '10';
    expect(inputValue(eventFrom(select))).toBe('10');
  });

  it('returns an empty string for anything else, rather than throwing', () => {
    expect(inputValue(eventFrom(document.createElement('div')))).toBe('');
    expect(inputValue(new Event('input'))).toBe('');
  });
});
