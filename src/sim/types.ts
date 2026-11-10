import type { Settlement } from '../town/properties';

export type Id = string;

export interface Traits {
  ambition: number; // 0..1
  charm: number; // 0..1
  diligence: number; // 0..1
}

export interface Person {
  id: Id;
  first: string;
  surname: string;
  sex: 'F' | 'M';
  birthYear: number;
  deathYear?: number;
  deathCause?: 'natural' | 'murdered';
  traits: Traits;
  parents: Id[];
  spouse?: Id;
  children: Id[];
  wealth: number;
  /** Public standing, -1..1. Affects elections and who people want to marry. */
  reputation: number;
}

export interface Business {
  id: Id;
  name: string;
  kind: string;
  foundedYear: number;
  closedYear?: number;
  owner?: Id;
  capital: number;
}

/** One thing that happened in town, as the Gazette reports it (see scenarios.ts). */
export type StoryCategory = 'romance' | 'feud' | 'business' | 'crime' | 'civic' | 'nature' | 'celebration' | 'tragedy' | 'arrival' | 'family' | 'uncanny';
export interface Story {
  id: string;
  year: number;
  template: string;
  category: StoryCategory;
  headline: string;
  body: string;
  /** The people it is about (first = the main one). */
  people: Id[];
  business?: Id;
  /** A thread that a later story can resolve (a missing person, an unsolved crime, a feud). */
  open?: boolean;
  /** The id of the earlier story this one follows up. */
  sequelOf?: string;
}

export interface World {
  seed: string;
  year: number;
  people: Record<Id, Person>;
  businesses: Record<Id, Business>;
  mayor?: Id;
  /** Physical town history, advanced with the simulation, independent of viewing order. */
  settlement?: Settlement;
  /** Everything the Gazette has reported so far, oldest first. */
  stories?: Story[];
  /** Deliberate changes to how much two people like each other (see social.ts). Key = pairKey. */
  bonds: Record<string, number>;
}

export type Intervention =
  | { kind: 'kill'; year: number; targetId: Id }
  | { kind: 'invest'; year: number; businessId: Id; amount: number }
  | { kind: 'sabotage'; year: number; businessId: Id }
  | { kind: 'introduce'; year: number; a: Id; b: Id }
  /** amount in -1..1: negative spreads a damaging rumor, positive runs a flattering campaign */
  | { kind: 'rumor'; year: number; targetId: Id; amount: number }
  | { kind: 'gift'; year: number; targetId: Id; amount: number };

export type EventKind =
  | 'birth'
  | 'death'
  | 'marriage'
  | 'immigration'
  | 'business_founded'
  | 'business_closed'
  | 'election'
  | 'intervention'
  | 'intervention_failed';

/** Structured history record. Future AI narration reads these; it never invents them. */
export interface SimEvent {
  year: number;
  kind: EventKind;
  subject?: Id;
  text: string;
}

export interface Timeline {
  seed: string;
  startYear: number;
  endYear: number;
  /** snapshots[i] is the world at year startYear + i */
  snapshots: World[];
  chronicle: SimEvent[];
}

export const fullName = (p: Person): string => `${p.first} ${p.surname}`;
export const isAlive = (p: Person): boolean => p.deathYear === undefined;
export const ageIn = (p: Person, year: number): number => year - p.birthYear;
