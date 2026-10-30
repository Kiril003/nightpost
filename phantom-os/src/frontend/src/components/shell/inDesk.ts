/**
 * «Ти всередині оболонки ПК» — для частин, що живуть і деінде (веб-месенджер
 * телефона, старі розкладки). У оболонці навігацію тримає док; дублювати її
 * всередині розділу не треба.
 */
import { createContext, useContext } from 'react';

export const InDeskContext = createContext(false);

export const useInDesk = (): boolean => useContext(InDeskContext);
