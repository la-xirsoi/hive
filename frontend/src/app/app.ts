import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/**
 * Application root. Deliberately empty of behaviour: routing decides what is on
 * screen, and the authenticated layout is a route (`ShellLayout`), not a
 * wrapper here, so unauthenticated screens do not render the app chrome.
 */
@Component({
  imports: [RouterOutlet],
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App {}
