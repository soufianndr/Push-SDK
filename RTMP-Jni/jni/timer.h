#ifndef __TIMER_H__
#define __TIMER_H__

#include <stdbool.h>
#define __USER_BLOCK__

#ifdef __USER_BLOCK__
///////////// Timer /////////////
#define YEAR ((((__DATE__ [7] - '0') * 10 + (__DATE__ [8] - '0')) * 10 \  
			+ (__DATE__ [9] - '0')) * 10 + (__DATE__ [10] - '0'))

#define MONTH (__DATE__ [2] == 'n' ? 0 \  
		: __DATE__ [2] == 'b' ? 1 \
		: __DATE__ [2] == 'r' ? (__DATE__ [0] == 'M' ? 2 : 3) \
		: __DATE__ [2] == 'y' ? 4 \
		: __DATE__ [2] == 'n' ? 5 \
		: __DATE__ [2] == 'l' ? 6 \
		: __DATE__ [2] == 'g' ? 7 \
		: __DATE__ [2] == 'p' ? 8 \
		: __DATE__ [2] == 't' ? 9 \
		: __DATE__ [2] == 'v' ? 10 : 11)

#define DAY ((__DATE__ [4] == ' ' ? 0 : __DATE__ [4] - '0') * 10 \  
		+ (__DATE__ [5] - '0'))

#define DATE_AS_INT (((YEAR - 1970) * 12 + MONTH) * 31 + DAY)  

bool needBlocked = false;
///////////// Timer /////////////
#endif // __USER_BLOCK__

#endif // __TIMER_H__
